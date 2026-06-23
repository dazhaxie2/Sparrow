package com.sparrow.chain.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparrow.chain.domain.model.Chain;
import com.sparrow.chain.domain.model.ChainEdge;
import com.sparrow.chain.domain.model.ChainNode;
import com.sparrow.chain.infrastructure.persistence.ChainEdgeMapper;
import com.sparrow.chain.infrastructure.persistence.ChainMapper;
import com.sparrow.chain.infrastructure.persistence.ChainNodeMapper;
import com.sparrow.chain.interfaces.dto.ChainDtos.ChainEdgeBrief;
import com.sparrow.chain.interfaces.dto.ChainDtos.ChainGraph;
import com.sparrow.chain.interfaces.dto.ChainDtos.ChainNodeBrief;
import com.sparrow.chain.interfaces.dto.ChainDtos.ChainSummary;
import com.sparrow.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 产业链专题服务。
 *
 * <p>只读查询 chain / chain_node / chain_edge 三表,组装供前端 sigma 渲染的图数据。
 * 数据由 sparrow-spider 的 chain_pipeline + chain_sync 写入,本服务不负责生产数据。</p>
 */
@Service
public class ChainService {

    private final ChainMapper chainMapper;
    private final ChainNodeMapper nodeMapper;
    private final ChainEdgeMapper edgeMapper;

    public ChainService(ChainMapper chainMapper, ChainNodeMapper nodeMapper, ChainEdgeMapper edgeMapper) {
        this.chainMapper = chainMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
    }

    /** 4 条产业链概览(含每条节点数)。 */
    public List<ChainSummary> listChains() {
        List<Chain> chains = chainMapper.selectList(
                new QueryWrapper<Chain>().orderByAsc("id"));
        return chains.stream().map(this::toSummary).toList();
    }

    /** 单条产业链详情(与列表项同一稳定契约,含当前节点数)。 */
    public ChainSummary chain(String slug) {
        return toSummary(findBySlug(slug));
    }

    /** 单条产业链的完整网络图(节点 + 边)。 */
    public ChainGraph chainGraph(String slug) {
        Chain chain = findBySlug(slug);
        Long chainId = chain.getId();
        List<ChainNodeBrief> nodes = nodeMapper.selectList(
                        new QueryWrapper<ChainNode>().eq("chain_id", chainId))
                .stream().map(ChainNodeBrief::from).toList();
        List<ChainEdgeBrief> edges = edgeMapper.selectList(
                        new QueryWrapper<ChainEdge>().eq("chain_id", chainId))
                .stream().map(ChainEdgeBrief::from).toList();
        return new ChainGraph(nodes, edges);
    }

    private Chain findBySlug(String slug) {
        Chain chain = chainMapper.selectOne(new QueryWrapper<Chain>().eq("slug", slug));
        if (chain == null) {
            throw new BizException(404, "产业链不存在: " + slug);
        }
        return chain;
    }

    private Integer countNodes(Long chainId) {
        Long count = nodeMapper.selectCount(
                new QueryWrapper<ChainNode>().eq("chain_id", chainId));
        return count == null ? 0 : count.intValue();
    }

    private ChainSummary toSummary(Chain chain) {
        return new ChainSummary(
                chain.getId(), chain.getSlug(), chain.getName(), chain.getDescription(),
                chain.getCoverColor(), countNodes(chain.getId()));
    }
}
