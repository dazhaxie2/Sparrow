package com.sparrow.ai;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
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

    private final MilvusProperties props;
    private volatile MilvusServiceClient client;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public MilvusStore(MilvusProperties props) {
        this.props = props;
    }

    public boolean ready() {
        return ready.get();
    }

    public synchronized void connect() {
        if (client == null) {
            client = new MilvusServiceClient(ConnectParam.newBuilder()
                    .withHost(props.host())
                    .withPort(props.port())
                    .build());
        }
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
