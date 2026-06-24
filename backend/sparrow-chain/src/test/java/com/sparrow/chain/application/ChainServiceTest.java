package com.sparrow.chain.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sparrow.chain.domain.model.Chain;
import com.sparrow.chain.domain.model.ChainEdge;
import com.sparrow.chain.domain.model.ChainNode;
import com.sparrow.chain.infrastructure.persistence.ChainEdgeMapper;
import com.sparrow.chain.infrastructure.persistence.ChainMapper;
import com.sparrow.chain.infrastructure.persistence.ChainNodeMapper;
import com.sparrow.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainServiceTest {

    private ChainMapper chainMapper;
    private ChainNodeMapper nodeMapper;
    private ChainEdgeMapper edgeMapper;
    private ChainService service;

    @BeforeEach
    void setUp() {
        chainMapper = mock(ChainMapper.class);
        nodeMapper = mock(ChainNodeMapper.class);
        edgeMapper = mock(ChainEdgeMapper.class);
        service = new ChainService(chainMapper, nodeMapper, edgeMapper);
    }

    @Test
    void listsChainsWithNodeCounts() {
        Chain chain = chain(1L, "nvidia-ai", "英伟达 / AI 芯片链");
        when(chainMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chain));
        // N+1 优化后:节点数来自一次 GROUP BY 聚合(selectMaps,列别名 chainId/cnt),而非逐条 selectCount。
        when(nodeMapper.selectMaps(any(Wrapper.class)))
                .thenReturn(List.of(Map.of("chainId", 1L, "cnt", 3L)));

        var result = service.listChains();

        assertEquals(1, result.size());
        assertEquals("nvidia-ai", result.get(0).slug());
        assertEquals(3, result.get(0).nodeCount());
    }

    @Test
    void listChainWithoutNodesCountsZero() {
        Chain chain = chain(2L, "empty-chain", "空链");
        when(chainMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chain));
        when(nodeMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        var result = service.listChains();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).nodeCount());
    }

    @Test
    void returnsDetailAndGraphWithLabels() {
        Chain chain = chain(1L, "nvidia-ai", "英伟达 / AI 芯片链");
        when(chainMapper.selectOne(any(Wrapper.class))).thenReturn(chain);
        when(nodeMapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        ChainNode node = new ChainNode();
        node.setId(11L);
        node.setName("台积电");
        node.setNodeType("代工厂");
        node.setImportance(8);
        when(nodeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(node));

        ChainEdge edge = new ChainEdge();
        edge.setFromId(11L);
        edge.setToId(12L);
        edge.setEdgeType("代工");
        edge.setProduct("GPU");
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(edge));

        assertEquals(2, service.chain("nvidia-ai").nodeCount());
        var graph = service.chainGraph("nvidia-ai");
        assertEquals("代工厂", graph.nodes().get(0).nodeTypeText());
        assertEquals("代工", graph.edges().get(0).edgeTypeText());
    }

    @Test
    void rejectsUnknownSlug() {
        when(chainMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        BizException error = assertThrows(BizException.class, () -> service.chain("missing"));
        assertEquals(404, error.getCode());
    }

    private static Chain chain(Long id, String slug, String name) {
        Chain chain = new Chain();
        chain.setId(id);
        chain.setSlug(slug);
        chain.setName(name);
        chain.setDescription("description");
        chain.setCoverColor("#76b900");
        return chain;
    }
}
