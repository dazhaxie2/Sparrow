package com.sparrow.graph.domain.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 百万级 LOD 布局坐标(SFDP 离线预计算结果)。
 * (node_id, level) 联合主键:同一节点在每个 LOD 层级都有坐标。
 * level 0 = 顶层簇代表点(远观),level 3 = 叶节点精确坐标(最深处)。
 */
@TableName("node_layout")
public class NodeLayout {

    @TableField("node_id")
    private Long nodeId;

    @TableField("cluster_id")
    private Long clusterId;

    private Integer level;

    private Double x;

    private Double y;

    public Long getNodeId() {
        return nodeId;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public Integer getLevel() {
        return level;
    }

    public Double getX() {
        return x;
    }

    public Double getY() {
        return y;
    }
}
