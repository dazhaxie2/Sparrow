package com.sparrow.ai.infrastructure.rag;

import com.sparrow.ai.infrastructure.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.GetCollStatResponseWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Milvus Standalone 的薄封装。连接失败不影响应用启动,
 * ready=false 时 AI 问答自动走关键词降级检索。
 */
@Component
public class MilvusStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusStore.class);

    /** 向量分批插入的批大小:控制单批装箱的 float 数(BATCH×dim),避免重建时堆内存尖峰。 */
    private static final int INSERT_BATCH = 1000;

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
     * 丢弃当前连接,下次 connect() 重建并重新解析服务名。
     * 用于 milvus 容器重建后 IP 变化:单例 gRPC channel 仍钉在旧 IP,
     * 必须丢弃重连才能跟随 Docker DNS 解析到新 IP。
     */
    private synchronized void reset() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignore) {
                // 仅为丢弃坏连接,关闭异常无需处理
            }
            client = null;
        }
        ready.set(false);
        chunkReady.set(false);
    }

    /** 执行有返回值的 Milvus 操作:失败则丢弃旧连接、重连一次再试(第二次失败抛给调用方)。 */
    private <T> T withReconnect(String op, Supplier<T> action) {
        connect();
        try {
            return action.get();
        } catch (Exception first) {
            log.warn("Milvus {} 失败,丢弃旧连接并重连重试: {}", op, first.getMessage());
            reset();
            connect();
            return action.get();
        }
    }

    /** 执行无返回值的 Milvus 操作(同上重连语义)。 */
    private void runWithReconnect(String op, Runnable action) {
        withReconnect(op, () -> {
            action.run();
            return null;
        });
    }

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
            reset();
            return false;
        }
    }

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

    public void rebuild(List<Long> nodeIds, List<float[]> vectors) {
        boolean chunksWereReady = chunkReady.get();
        runWithReconnect("重建节点向量集合", () -> doRebuild(nodeIds, vectors));
        if (chunksWereReady) {
            chunkReady.set(true);
        }
    }

    private void doRebuild(List<Long> nodeIds, List<float[]> vectors) {
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

        // 分批插入:每批 INSERT_BATCH 条,装箱后的 List<List<Float>> 与底层 gRPC FloatArray
        // 随批生成、随批回收;避免 10935×2048 个 float 一次性装箱(数百 MB)把堆压爆
        // (此处曾是 milvus-sync 线程 OutOfMemoryError 的根因)。
        int total = nodeIds.size();
        for (int start = 0; start < total; start += INSERT_BATCH) {
            int end = Math.min(start + INSERT_BATCH, total);
            client.insert(InsertParam.newBuilder()
                    .withCollectionName(name)
                    .withFields(List.of(
                            new InsertParam.Field("node_id", nodeIds.subList(start, end)),
                            new InsertParam.Field("embedding", toFloatRows(vectors.subList(start, end)))))
                    .build());
        }
        client.flush(FlushParam.newBuilder().addCollectionName(name).build());

        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexType(IndexType.FLAT)
                .withMetricType(MetricType.COSINE)
                .build());
        client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(name).build());

        ready.set(true);
        log.info("Milvus 集合已重建并载入: {} 条向量, dim={} (每批 {} 插入)", nodeIds.size(), dim, INSERT_BATCH);
    }

    public void rebuildChunks(List<String> chunkIds, List<String> codes, List<String> urls,
                              List<String> texts, List<float[]> vectors) {
        boolean nodesWereReady = ready.get();
        runWithReconnect("重建语料块向量集合",
                () -> doRebuildChunks(chunkIds, codes, urls, texts, vectors));
        if (nodesWereReady) {
            ready.set(true);
        }
    }

    private void doRebuildChunks(List<String> chunkIds, List<String> codes, List<String> urls,
                                 List<String> texts, List<float[]> vectors) {
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

        // 同 rebuild():分批插入,避免一次性装箱所有 chunk 向量撑爆堆。
        int total = chunkIds.size();
        for (int start = 0; start < total; start += INSERT_BATCH) {
            int end = Math.min(start + INSERT_BATCH, total);
            client.insert(InsertParam.newBuilder()
                    .withCollectionName(name)
                    .withFields(List.of(
                            new InsertParam.Field("chunk_id", chunkIds.subList(start, end)),
                            new InsertParam.Field("code", codes.subList(start, end)),
                            new InsertParam.Field("url", urls.subList(start, end)),
                            new InsertParam.Field("text", texts.subList(start, end)),
                            new InsertParam.Field("embedding", toFloatRows(vectors.subList(start, end)))))
                    .build());
        }
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
        boolean nodesWereReady = ready.get();
        try {
            List<ChunkHit> hits = withReconnect("语料块向量搜索", () -> doSearchChunks(queryVector, topK));
            chunkReady.set(true);
            if (nodesWereReady) {
                ready.set(true);
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus 语料块向量搜索不可用,降级为空结果: {}", e.getMessage());
            reset();
            return List.of();
        }
    }

    private List<ChunkHit> doSearchChunks(float[] queryVector, int topK) {
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
        if (!ready.get()) {
            return List.of();
        }
        boolean chunksWereReady = chunkReady.get();
        try {
            List<Long> hits = withReconnect("节点向量搜索", () -> doSearch(queryVector, topK));
            ready.set(true);
            if (chunksWereReady) {
                chunkReady.set(true);
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus 节点向量搜索不可用,降级为空结果: {}", e.getMessage());
            reset();
            return List.of();
        }
    }

    private List<Long> doSearch(float[] queryVector, int topK) {
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

    /** float[] 批量装箱为 Milvus 客户端要求的 List<List<Float>>(仅在单批范围内存活,随批回收)。 */
    private static List<List<Float>> toFloatRows(List<float[]> vectors) {
        List<List<Float>> rows = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            List<Float> row = new ArrayList<>(v.length);
            for (float f : v) {
                row.add(f);
            }
            rows.add(row);
        }
        return rows;
    }
}
