package com.sparrow.graph.infrastructure.neo4j;

import com.sparrow.graph.domain.model.TechEdge;
import com.sparrow.graph.domain.model.TechNode;
import com.sparrow.graph.infrastructure.persistence.TechEdgeMapper;
import com.sparrow.graph.infrastructure.persistence.TechNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL → Neo4j 迁移器。使用分批 UNWIND Cypher 替代 SDN saveAll 整图写入,
 * 使内存占用从 O(全量) 降到 O(batch),wiki 级数据(10k 节点 / 97k 边)下不再 OOM。
 */
@Component
public class Neo4jMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jMigrator.class);

    /** 每批写入 Neo4j 的节点数;detail/summary 为长文本,500 约 ~2–5MB/批,安全。 */
    private static final int NODE_BATCH = 500;
    /** 每批写入 Neo4j 的边数;边只含 (fromId, toId),极轻,1000 约 ~50KB/批。 */
    private static final int EDGE_BATCH = 1000;

    private static final String UPSERT_NODE_CYPHER =
            "UNWIND $batch AS row "
                    + "MERGE (n:TechNode {id: row.id}) "
                    + "SET n.code = row.code, n.name = row.name, n.era = row.era, "
                    + "n.era_rank = row.era_rank, n.year_label = row.year_label, "
                    + "n.summary = row.summary, n.detail = row.detail, "
                    + "n.premium = row.premium, n.category = row.category, n.importance = row.importance";

    private static final String CREATE_EDGE_CYPHER =
            "UNWIND $batch AS row "
                    + "MATCH (from:TechNode {id: row.fromId}) "
                    + "MATCH (to:TechNode {id: row.toId}) "
                    + "MERGE (from)-[:REQUIRES]->(to)";

    private final TechNodeMapper nodeMapper;
    private final TechEdgeMapper edgeMapper;
    private final NeoTechNodeRepository neoRepo;
    private final Neo4jClient neo4jClient;

    public Neo4jMigrator(TechNodeMapper nodeMapper, TechEdgeMapper edgeMapper,
                         NeoTechNodeRepository neoRepo, Neo4jClient neo4jClient) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.neoRepo = neoRepo;
        this.neo4jClient = neo4jClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 维基级数据集下迁移既慢又易 OOM;允许用环境变量关闭启动迁移,
        // 让 graph 直接复用 Neo4j 卷中的现有数据(压测/快速重启场景)。
        if ("false".equalsIgnoreCase(System.getenv("GRAPH_MIGRATE_ON_BOOT"))) {
            log.warn("GRAPH_MIGRATE_ON_BOOT=false,跳过 MySQL->Neo4j 启动迁移,复用 Neo4j 现有数据");
            return;
        }
        try {
            ensureSchema();
            migrateIfNeeded();
        } catch (Exception e) {
            log.error("Neo4j 迁移失败,graph 服务将尝试使用已有数据: {}", e.getMessage());
        }
    }

    void ensureSchema() {
        neo4jClient.query(
                "CREATE CONSTRAINT tech_node_id_unique IF NOT EXISTS "
                        + "FOR (n:TechNode) REQUIRE n.id IS UNIQUE").run();
        neo4jClient.query(
                "CREATE INDEX tech_node_era_rank IF NOT EXISTS "
                        + "FOR (n:TechNode) ON (n.era_rank)").run();
        neo4jClient.query(
                "CREATE INDEX tech_node_code IF NOT EXISTS "
                        + "FOR (n:TechNode) ON (n.code)").run();
        neo4jClient.query(
                "CREATE INDEX tech_node_category IF NOT EXISTS "
                        + "FOR (n:TechNode) ON (n.category)").run();
        neo4jClient.query(
                "CREATE INDEX tech_node_importance IF NOT EXISTS "
                        + "FOR (n:TechNode) ON (n.importance)").run();
    }

    public void importFromMysql() {
        ensureSchema();
        migrate(true);
    }

    public void migrateIfNeeded() {
        migrate(false);
    }

    private void migrate(boolean force) {
        List<TechNode> mysqlNodes = nodeMapper.selectList(null);
        List<TechEdge> mysqlEdges = edgeMapper.selectList(null);

        long neoNodeCount = neoRepo.countAll();
        if (!force && neoNodeCount == mysqlNodes.size() && neoRepo.countEdges() == mysqlEdges.size()) {
            log.info("Neo4j 数据已是最新({} 节点 / {} 边),跳过迁移", neoNodeCount, mysqlEdges.size());
            return;
        }

        log.info("开始 MySQL -> Neo4j 分批迁移: {} 节点, {} 边 (NODE_BATCH={}, EDGE_BATCH={})",
                mysqlNodes.size(), mysqlEdges.size(), NODE_BATCH, EDGE_BATCH);

        if (force) {
            neoRepo.deleteAllNodes();
        }

        // ── 分批写入节点 ──
        int nodeCount = 0;
        List<Map<String, Object>> nodeBatch = new ArrayList<>(NODE_BATCH);
        for (TechNode n : mysqlNodes) {
            nodeBatch.add(toNodeMap(n));
            if (nodeBatch.size() >= NODE_BATCH) {
                neo4jClient.query(UPSERT_NODE_CYPHER).bind(nodeBatch).to("batch").run();
                nodeCount += nodeBatch.size();
                nodeBatch.clear();
            }
        }
        if (!nodeBatch.isEmpty()) {
            neo4jClient.query(UPSERT_NODE_CYPHER).bind(nodeBatch).to("batch").run();
            nodeCount += nodeBatch.size();
        }
        log.info("节点写入完成: {}", nodeCount);

        // ── 分批写入边(只保留两端节点都存在的边) ──
        Map<Long, Boolean> nodeIdSet = new HashMap<>(mysqlNodes.size());
        for (TechNode n : mysqlNodes) {
            nodeIdSet.put(n.getId(), Boolean.TRUE);
        }

        int edgeCount = 0;
        List<Map<String, Object>> edgeBatch = new ArrayList<>(EDGE_BATCH);
        for (TechEdge e : mysqlEdges) {
            if (nodeIdSet.containsKey(e.getFromId()) && nodeIdSet.containsKey(e.getToId())) {
                Map<String, Object> row = new HashMap<>(2);
                row.put("fromId", e.getFromId());
                row.put("toId", e.getToId());
                edgeBatch.add(row);
                if (edgeBatch.size() >= EDGE_BATCH) {
                    neo4jClient.query(CREATE_EDGE_CYPHER).bind(edgeBatch).to("batch").run();
                    edgeCount += edgeBatch.size();
                    edgeBatch.clear();
                }
            }
        }
        if (!edgeBatch.isEmpty()) {
            neo4jClient.query(CREATE_EDGE_CYPHER).bind(edgeBatch).to("batch").run();
            edgeCount += edgeBatch.size();
        }
        log.info("Neo4j 迁移完成: {} 节点, {} 边", neoRepo.countAll(), neoRepo.countEdges());
    }

    private static Map<String, Object> toNodeMap(TechNode n) {
        Map<String, Object> row = new HashMap<>(11);
        row.put("id", n.getId());
        row.put("code", n.getCode());
        row.put("name", n.getName());
        row.put("era", n.getEra());
        row.put("era_rank", n.getEraRank());
        row.put("year_label", n.getYearLabel());
        row.put("summary", n.getSummary());
        row.put("detail", n.getDetail());
        row.put("premium", n.getPremium());
        row.put("category", n.getCategory());
        row.put("importance", n.getImportance());
        return row;
    }
}
