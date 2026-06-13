package com.sparrow.ai.infrastructure.rag;

import com.sparrow.ai.infrastructure.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.response.GetCollStatResponseWrapper;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Milvus Standalone 的薄封装。连接失败不影响应用启动,
 * ready=false 时 AI 问答自动走关键词降级检索。
 */
@Component
public class MilvusStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusStore.class);

    /** rag_document 语料块的一次检索命中,text 即入库时的切块原文 */
    public record ChunkHit(String code, String url, String text) {
    }

    private final MilvusProperties props;
    private volatile MilvusServiceClient client;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean chunkReady = new AtomicBoolean(false);

    public MilvusStore(MilvusProperties props) {
        this.props = props;
    }

    public boolean ready() {
        return ready.get();
    }

    public boolean chunkReady() {
        return chunkReady.get();
    }

    private String chunkCollection() {
        return props.collection() + "_chunk";
    }

    public synchronized void connect() {
        if (client == null) {
            client = new MilvusServiceClient(ConnectParam.newBuilder()
                    .withHost(props.host())
                    .withPort(props.port())
                    .build());
        }
    }

    /**
     * 内容未变时复用 Milvus 卷里已持久化的向量:仅校验集合行数并 load,不重新 embedding。
     * 行数与期望一致才算命中;任一不符返回 false,交回 rebuild 重建。
     */
    public boolean tryLoadExisting(int expectedNodes, int expectedChunks) {
        try {
            connect();
            if (collectionCount(props.collection()) != expectedNodes) {
                return false;
            }
            if (expectedChunks > 0 && collectionCount(chunkCollection()) != expectedChunks) {
                return false;
            }
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(props.collection()).build());
            ready.set(true);
            if (expectedChunks > 0) {
                client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(chunkCollection()).build());
                chunkReady.set(true);
            } else {
                chunkReady.set(false);
            }
            return true;
        } catch (Exception e) {
            log.warn("复用已有 Milvus 集合失败,将重新构建: {}", e.getMessage());
            ready.set(false);
            chunkReady.set(false);
            return false;
        }
    }

    /** 集合行数;集合不存在返回 -1 */
    private long collectionCount(String name) {
        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(name).build());
        if (!Boolean.TRUE.equals(has.getData())) {
            return -1;
        }
        R<GetCollectionStatisticsResponse> stat = client.getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder()
                        .withCollectionName(name).withFlush(true).build());
        return new GetCollStatResponseWrapper(stat.getData()).getRowCount();
    }

    /** 重建集合并写入全部节点向量(幂等:先 drop 再建) */
    public void rebuild(List<Long> nodeIds, List<float[]> vectors) {
        connect();
        String name = props.collection();
        int dim = vectors.get(0).length;

        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(name).build());
        if (Boolean.TRUE.equals(has.getData())) {
            client.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName(name).build());
        }

        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(name)
                .addFieldType(FieldType.newBuilder()
                        .withName("node_id").withDataType(DataType.Int64).withPrimaryKey(true).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding").withDataType(DataType.FloatVector).withDimension(dim).build())
                .build());

        List<List<Float>> floatVectors = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            List<Float> row = new ArrayList<>(v.length);
            for (float f : v) {
                row.add(f);
            }
            floatVectors.add(row);
        }
        client.insert(InsertParam.newBuilder()
                .withCollectionName(name)
                .withFields(List.of(
                        new InsertParam.Field("node_id", nodeIds),
                        new InsertParam.Field("embedding", floatVectors)))
                .build());
        client.flush(FlushParam.newBuilder().addCollectionName(name).build());

        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexType(IndexType.FLAT) // 万级以下节点 FLAT 精确检索即可
                .withMetricType(MetricType.COSINE)
                .build());
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(name).build());

        ready.set(true);
        log.info("Milvus 集合已重建并载入: {} 条向量, dim={}", nodeIds.size(), dim);
    }

    /**
     * 重建 rag_document 语料块集合(幂等)。chunkIds 形如 {code}#{chunkNo}。
     * 传空列表表示语料表不存在或为空,只清掉旧集合。
     */
    public void rebuildChunks(List<String> chunkIds, List<String> codes, List<String> urls,
                              List<String> texts, List<float[]> vectors) {
        connect();
        String name = chunkCollection();

        R<Boolean> has = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(name).build());
        if (Boolean.TRUE.equals(has.getData())) {
            client.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName(name).build());
        }
        if (chunkIds.isEmpty()) {
            chunkReady.set(false);
            return;
        }
        int dim = vectors.get(0).length;

        client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(name)
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_id").withDataType(DataType.VarChar)
                        .withMaxLength(128).withPrimaryKey(true).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("code").withDataType(DataType.VarChar).withMaxLength(64).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("url").withDataType(DataType.VarChar).withMaxLength(512).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("text").withDataType(DataType.VarChar).withMaxLength(8192).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding").withDataType(DataType.FloatVector).withDimension(dim).build())
                .build());

        List<List<Float>> floatVectors = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            List<Float> row = new ArrayList<>(v.length);
            for (float f : v) {
                row.add(f);
            }
            floatVectors.add(row);
        }
        client.insert(InsertParam.newBuilder()
                .withCollectionName(name)
                .withFields(List.of(
                        new InsertParam.Field("chunk_id", chunkIds),
                        new InsertParam.Field("code", codes),
                        new InsertParam.Field("url", urls),
                        new InsertParam.Field("text", texts),
                        new InsertParam.Field("embedding", floatVectors)))
                .build());
        client.flush(FlushParam.newBuilder().addCollectionName(name).build());

        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexType(IndexType.FLAT)
                .withMetricType(MetricType.COSINE)
                .build());
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(name).build());

        chunkReady.set(true);
        log.info("Milvus 语料块集合已重建并载入: {} 条向量, dim={}", chunkIds.size(), dim);
    }

    public List<ChunkHit> searchChunks(float[] queryVector, int topK) {
        if (!chunkReady.get()) {
            return List.of();
        }
        List<Float> vec = new ArrayList<>(queryVector.length);
        for (float f : queryVector) {
            vec.add(f);
        }
        R<SearchResults> resp = client.search(SearchParam.newBuilder()
                .withCollectionName(chunkCollection())
                .withMetricType(MetricType.COSINE)
                .withVectorFieldName("embedding")
                .withVectors(List.of(vec))
                .withTopK(topK)
                .withOutFields(List.of("code", "url", "text"))
                .build());
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<?> codes = wrapper.getFieldData("code", 0);
        List<?> urls = wrapper.getFieldData("url", 0);
        List<?> texts = wrapper.getFieldData("text", 0);
        List<ChunkHit> hits = new ArrayList<>(codes.size());
        for (int i = 0; i < codes.size(); i++) {
            hits.add(new ChunkHit((String) codes.get(i), (String) urls.get(i), (String) texts.get(i)));
        }
        return hits;
    }

    public List<Long> search(float[] queryVector, int topK) {
        List<Float> vec = new ArrayList<>(queryVector.length);
        for (float f : queryVector) {
            vec.add(f);
        }
        R<SearchResults> resp = client.search(SearchParam.newBuilder()
                .withCollectionName(props.collection())
                .withMetricType(MetricType.COSINE)
                .withVectorFieldName("embedding")
                .withVectors(List.of(vec))
                .withTopK(topK)
                .build());
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        return wrapper.getIDScore(0).stream().map(s -> s.getLongID()).toList();
    }
}
