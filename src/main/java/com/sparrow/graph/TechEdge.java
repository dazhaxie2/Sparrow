package com.sparrow.graph;

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

    public Long getId() {
        return id;
    }

    public Long getFromId() {
        return fromId;
    }

    public Long getToId() {
        return toId;
    }
}
