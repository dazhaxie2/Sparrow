package com.sparrow.graph.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 「应用与产业链」AI 判定结果缓存(node_application 表)。
 *
 * <p>材料/化合物节点的下游应用邻居由 sparrow-ai 的 LLM 判定后回写。
 * 首次请求触发计算,之后命中缓存秒出。表可能尚未在存量库创建(未跑 migrate-applications.sql),
 * 故 {@link #tableReady()} 先探测表存在性,缺失时返回空列表,不阻断详情主流程。</p>
 *
 * <p>幂等性:{@link #saveAll} 先 delete by node_id 再批量插入,
 * 同一节点可被反复重算而不产生重复行。</p>
 */
@Repository
public class NodeApplicationRepository {

    private static final Logger log = LoggerFactory.getLogger(NodeApplicationRepository.class);

    private final JdbcTemplate jdbc;
    private volatile Boolean tableExists;

    public NodeApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 表已就绪;首次调用时探测并缓存结果。表缺失返回 false(降级为空)。 */
    private boolean tableReady() {
        if (tableExists != null) return tableExists;
        synchronized (this) {
            if (tableExists != null) return tableExists;
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = 'node_application'",
                    Integer.class);
            tableExists = count != null && count > 0;
            if (!tableExists) {
                log.info("node_application 表不存在,「应用与产业链」功能降级为空(请执行 backend/scripts/migrate-applications.sql)");
            }
            return tableExists;
        }
    }

    /** 返回该节点已缓存的应用邻居 id;未缓存或表缺失返回空列表。 */
    public List<Long> findAppIds(Long nodeId) {
        if (!tableReady()) return List.of();
        return jdbc.queryForList(
                "SELECT app_node_id FROM node_application WHERE node_id = ?",
                Long.class, nodeId);
    }

    /** 缓存某节点的应用邻居 id(幂等:先删后插,支持重算)。空列表仅做清理。 */
    public void saveAll(Long nodeId, List<Long> appIds) {
        if (!tableReady()) return;
        jdbc.update("DELETE FROM node_application WHERE node_id = ?", nodeId);
        if (appIds == null || appIds.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>(appIds.size());
        for (Long appId : appIds) {
            if (appId != null) batch.add(new Object[]{nodeId, appId});
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT IGNORE INTO node_application (node_id, app_node_id) VALUES (?, ?)",
                    batch);
        }
    }
}
