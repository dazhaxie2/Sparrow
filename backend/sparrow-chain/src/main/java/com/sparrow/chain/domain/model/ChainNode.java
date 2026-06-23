package com.sparrow.chain.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 供应链节点:核心公司 / 供应商 / 代工厂 / 材料商。 */
@TableName("chain_node")
public class ChainNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chainId;
    private String name;
    /** 核心公司/供应商/代工厂/材料商 */
    private String nodeType;
    private String summary;
    private Integer importance;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Integer getImportance() { return importance; }
    public void setImportance(Integer importance) { this.importance = importance; }
}
