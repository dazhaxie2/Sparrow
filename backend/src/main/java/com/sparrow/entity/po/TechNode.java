package com.sparrow.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tech_node")
public class TechNode {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String code;

    private String name;

    private String era;

    @TableField("era_rank")
    private Integer eraRank;

    @TableField("year_label")
    private String yearLabel;

    private String summary;

    private String detail;

    private Boolean premium;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getEra() {
        return era;
    }

    public Integer getEraRank() {
        return eraRank;
    }

    public String getYearLabel() {
        return yearLabel;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public Boolean getPremium() {
        return premium;
    }
}
