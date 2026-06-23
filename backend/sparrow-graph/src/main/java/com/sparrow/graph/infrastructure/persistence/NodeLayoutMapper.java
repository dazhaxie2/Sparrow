package com.sparrow.graph.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * node_layout 坐标表读取(百万级 LOD 瓦片)。
 * 复合主键 (node_id, level),不走 BaseMapper,用 @Select 注解查询。
 */
@Mapper
public interface NodeLayoutMapper {

    /**
     * 指定层级指定簇内的可渲染节点(join tech_node 补 category/importance)。
     * idx_cluster_level 覆盖 (cluster_id, level) 过滤。
     */
    @Select("""
            SELECT l.node_id AS id, l.cluster_id AS clusterId, l.x, l.y,
                   n.name, n.category, n.importance
            FROM node_layout l
            JOIN tech_node n ON n.id = l.node_id
            WHERE l.cluster_id = #{clusterId} AND l.level = #{level}
            """)
    List<Map<String, Object>> tileNodes(@Param("level") int level, @Param("clusterId") long clusterId);

    /**
     * 顶层全景(level 0):所有簇代表点。clusterId 参数忽略,返回全部顶层代表点。
     * 用于"远观"视图 —— 几十个簇心点。
     */
    @Select("""
            SELECT l.node_id AS id, l.cluster_id AS clusterId, l.x, l.y,
                   n.name, n.category, n.importance
            FROM node_layout l
            JOIN tech_node n ON n.id = l.node_id
            WHERE l.level = 0
            """)
    List<Map<String, Object>> topLevelNodes();

    /** 所有社区簇及成员数；level 0 提供代表点，level 3 提供完整成员计数。 */
    @Select("""
            SELECT l0.node_id AS id, l0.cluster_id AS clusterId, l0.x, l0.y,
                   n.name, n.category, n.importance, COUNT(l3.node_id) AS nodeCount
            FROM node_layout l0
            JOIN tech_node n ON n.id = l0.node_id
            JOIN node_layout l3
              ON l3.cluster_id = l0.cluster_id AND l3.level = 3
            WHERE l0.level = 0
            GROUP BY l0.node_id, l0.cluster_id, l0.x, l0.y,
                     n.name, n.category, n.importance
            ORDER BY nodeCount DESC, l0.cluster_id ASC
            """)
    List<Map<String, Object>> clusterSummaries();

    /**
     * 簇内边:两端节点都属于指定簇(同 cluster_id)的边。
     * 用于 level 3 叶节点瓦片的边渲染。
     */
    @Select("""
            SELECT e.from_id AS `from`, e.to_id AS `to`, e.relation AS relation
            FROM tech_edge e
            JOIN node_layout l1 ON l1.node_id = e.from_id
            JOIN node_layout l2 ON l2.node_id = e.to_id
            WHERE l1.cluster_id = #{clusterId} AND l2.cluster_id = #{clusterId}
              AND l1.level = #{level} AND l2.level = #{level}
            """)
    List<Map<String, Object>> tileEdges(@Param("level") int level, @Param("clusterId") long clusterId);

    /** 布局覆盖率:有坐标的节点数(诊断布局管线是否跑完)。 */
    @Select("SELECT COUNT(DISTINCT node_id) FROM node_layout WHERE level = #{level}")
    long countLaidOutNodes(@Param("level") int level);
}
