package com.sparrow.graph.infrastructure.neo4j;

import com.sparrow.graph.domain.model.NeoTechNode;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class Neo4jMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jMigrator.class);

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
        // 维基级数据集下 SDN saveAll 整图迁移既慢又易 OOM;允许用环境变量关闭启动迁移,
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

        log.info("开始 MySQL -> Neo4j 迁移: {} 节点, {} 边", mysqlNodes.size(), mysqlEdges.size());

        if (force) {
            neoRepo.deleteAllNodes();
        }

        Map<Long, NeoTechNode> neoMap = new HashMap<>();
        for (TechNode n : mysqlNodes) {
            NeoTechNode neo = force ? null : neoRepo.findById(n.getId()).orElse(null);
            if (neo == null) {
                neo = new NeoTechNode();
            }
            neo.setId(n.getId());
            neo.setCode(n.getCode());
            neo.setName(n.getName());
            neo.setEra(n.getEra());
            neo.setEraRank(n.getEraRank());
            neo.setYearLabel(n.getYearLabel());
            neo.setSummary(n.getSummary());
            neo.setDetail(n.getDetail());
            neo.setPremium(n.getPremium());
            neo.setCategory(n.getCategory());
            neo.setImportance(n.getImportance());
            neoMap.put(n.getId(), neo);
        }
        neoRepo.saveAll(neoMap.values());

        for (TechEdge e : mysqlEdges) {
            NeoTechNode dependent = neoMap.get(e.getToId());
            NeoTechNode prerequisite = neoMap.get(e.getFromId());
            if (dependent != null && prerequisite != null) {
                dependent.getRequires().add(prerequisite);
            }
        }
        neoRepo.saveAll(neoMap.values());

        log.info("Neo4j 迁移完成: {} 节点, {} 边",
                neoRepo.countAll(), neoRepo.countEdges());
    }
}
