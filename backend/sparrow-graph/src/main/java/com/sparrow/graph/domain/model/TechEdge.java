package com.sparrow.graph.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tech_edge")
public class TechEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("from_id")
    private Long fromId;

    @TableField("to_id")
    private Long toId;

    /** 关系类型:0=依赖/前置(默认),1=结构/分类归属。见 tech_edge.relation。 */
    private Integer relation;

    public Long getId() {
        return id;
    }

    public Long getFromId() {
        return fromId;
    }

    public Long getToId() {
        return toId;
    }

    public Integer getRelation() {
        return relation;
    }
}
