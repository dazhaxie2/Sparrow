package com.sparrow.chain.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 供应链边:from 供货给 to(有向)。edge_type 区分供货/代工/材料供应/授权。 */
@TableName("chain_edge")
public class ChainEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chainId;
    private Long fromId;
    private Long toId;
    /** 供货/代工/材料供应/授权 */
    private String edgeType;
    private String product;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChainId() { return chainId; }
    public void setChainId(Long chainId) { this.chainId = chainId; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public String getEdgeType() { return edgeType; }
    public void setEdgeType(String edgeType) { this.edgeType = edgeType; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
}
