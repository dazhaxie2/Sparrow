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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 产业链专题服务。
 *
 * <p>只读查询 chain / chain_node / chain_edge 三表,组装供前端 sigma 渲染的图数据。
 * 数据由 sparrow-spider 的 chain_pipeline + chain_sync 写入,本服务不负责生产数据。</p>
 *
 * <p>性能:产业链是读多写少的静态数据,三个查询接口结果用进程内缓存
 * (ConcurrentMapCacheManager,无额外依赖)缓存,并按 {@code chain.cache.ttl-ms}
 * 周期清空以吸收爬虫同步的更新;列表节点数用一次聚合查询避免 N+1。</p>
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

    /** 产业链概览(含每条节点数)。节点数用一次 GROUP BY 聚合,避免逐条 count 的 N+1。 */
    @Cacheable("chains")
    public List<ChainSummary> listChains() {
        List<Chain> chains = chainMapper.selectList(
                new QueryWrapper<Chain>().orderByAsc("id"));
        Map<Long, Integer> nodeCounts = nodeCountByChain();
        return chains.stream()
                .map(chain -> toSummary(chain, nodeCounts.getOrDefault(chain.getId(), 0)))
                .toList();
    }

    /** 单条产业链详情(与列表项同一稳定契约,含当前节点数)。 */
    @Cacheable(value = "chain", key = "#slug")
    public ChainSummary chain(String slug) {
        Chain chain = findBySlug(slug);
        return toSummary(chain, countNodes(chain.getId()));
    }

    /** 单条产业链的完整网络图(节点 + 边)。 */
    @Cacheable(value = "chainGraph", key = "#slug")
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

    /** 周期清空产业链缓存,使爬虫同步的更新在 TTL 内自然生效(默认 5 分钟)。 */
    @CacheEvict(value = {"chains", "chain", "chainGraph"}, allEntries = true)
    @Scheduled(fixedRateString = "${chain.cache.ttl-ms:300000}")
    public void evictChainCaches() {
        // 缓存由注解清空,方法体留空即可。
    }

    private Chain findBySlug(String slug) {
        Chain chain = chainMapper.selectOne(new QueryWrapper<Chain>().eq("slug", slug));
        if (chain == null) {
            throw new BizException(404, "产业链不存在: " + slug);
        }
        return chain;
    }

    /**
     * 各产业链的节点数(chain_id → count),一次聚合查询。
     * 列别名用无下划线的 chainId/cnt,避免 selectMaps 的 map key 受
     * mapUnderscoreToCamelCase 配置影响导致取值为 null。
     */
    private Map<Long, Integer> nodeCountByChain() {
        return nodeMapper.selectMaps(
                        new QueryWrapper<ChainNode>().select("chain_id AS chainId", "COUNT(*) AS cnt").groupBy("chain_id"))
                .stream()
                .filter(row -> row.get("chainId") != null)
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("chainId")).longValue(),
                        row -> ((Number) row.get("cnt")).intValue()));
    }

    private Integer countNodes(Long chainId) {
        Long count = nodeMapper.selectCount(
                new QueryWrapper<ChainNode>().eq("chain_id", chainId));
        return count == null ? 0 : count.intValue();
    }

    private ChainSummary toSummary(Chain chain, int nodeCount) {
        return new ChainSummary(
                chain.getId(), chain.getSlug(), chain.getName(), chain.getDescription(),
                chain.getCoverColor(), nodeCount);
    }
}
