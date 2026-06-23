package com.sparrow.chain.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 产业链主表:每条对应一条独立供应链(英伟达链/苹果链/特斯拉链/SpaceX 链)。 */
@TableName("chain")
public class Chain {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String slug;
    private String name;
    private String description;
    private String coverColor;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverColor() { return coverColor; }
    public void setCoverColor(String coverColor) { this.coverColor = coverColor; }
}
