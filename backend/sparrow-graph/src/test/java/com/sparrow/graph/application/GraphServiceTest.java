package com.sparrow.graph.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.graph.domain.model.NeoTechNode;
import com.sparrow.graph.infrastructure.client.UserClient;
import com.sparrow.graph.infrastructure.event.GraphEventPublisher;
import com.sparrow.graph.infrastructure.neo4j.NeoEdgeRecord;
import com.sparrow.graph.infrastructure.neo4j.NeoTechNodeRepository;
import com.sparrow.graph.infrastructure.persistence.TechNodeMapper;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphServiceTest {

    private NeoTechNodeRepository neoRepo;
    private TechNodeMapper nodeMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private UserClient userClient;
    private GraphEventPublisher eventPublisher;
    private GraphService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        neoRepo = org.mockito.Mockito.mock(NeoTechNodeRepository.class);
        nodeMapper = org.mockito.Mockito.mock(TechNodeMapper.class);
        redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        userClient = org.mockito.Mockito.mock(UserClient.class);
        eventPublisher = org.mockito.Mockito.mock(GraphEventPublisher.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new GraphService(neoRepo, nodeMapper, redis, new ObjectMapper(),
                userClient, eventPublisher);
    }

    @Test
    void treeReadsFromNeoAndCachesOnMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(neoRepo.findAllOrdered()).thenReturn(List.of(
                neoNode(1L, "fire", "火", false, "detail"),
                neoNode(2L, "tool", "石器", false, "detail")));
        when(neoRepo.findAllEdges()).thenReturn(List.of(new NeoEdgeRecord(1L, 2L)));

        Tree tree = service.tree();

        assertEquals(2, tree.nodes().size());
        assertEquals(1, tree.edges().size());
        verify(valueOps).set(anyString(), anyString(), any());
    }

    @Test
    void treeReadsFromCacheWhenHit() throws Exception {
        Tree cached = new Tree(List.of(), List.of());
        when(valueOps.get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(cached));

        Tree tree = service.tree();

        assertEquals(0, tree.nodes().size());
        verify(neoRepo, never()).findAllOrdered();
    }

    @Test
    void nodeDetailLocksPremiumForNonMember() {
        when(neoRepo.findByNodeId(41L)).thenReturn(Optional.of(
                neoNode(41L, "steam_engine", "蒸汽机", true, "机密详情")));
        when(neoRepo.findDirectPrerequisites(41L)).thenReturn(List.of());
        when(neoRepo.findDirectUnlocks(41L)).thenReturn(List.of());

        NodeDetail detail = service.nodeDetail(41L, null);

        assertTrue(detail.locked());
        assertNull(detail.detail());
    }

    @Test
    void nodeDetailUnlocksPremiumForMember() {
        when(neoRepo.findByNodeId(41L)).thenReturn(Optional.of(
                neoNode(41L, "steam_engine", "蒸汽机", true, "机密详情")));
        when(neoRepo.findDirectPrerequisites(41L)).thenReturn(List.of());
        when(neoRepo.findDirectUnlocks(41L)).thenReturn(List.of());
        Map<String, Object> membership = new HashMap<>();
        membership.put("member", true);
        when(userClient.membership(eq(42L))).thenReturn(ApiResponse.ok(membership));

        NodeDetail detail = service.nodeDetail(41L, 42L);

        assertFalse(detail.locked());
        assertEquals("机密详情", detail.detail());
    }

    @Test
    void nodeDetailThrowsWhenMissing() {
        when(neoRepo.findByNodeId(999L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.nodeDetail(999L, null));
    }

    @Test
    void prerequisiteChainThrowsWhenMissing() {
        when(neoRepo.existsByNodeId(999L)).thenReturn(false);
        assertThrows(BizException.class, () -> service.prerequisiteChain(999L));
    }

    @Test
    void requestReindexEvictsCacheAndPublishesEvent() {
        when(neoRepo.countAll()).thenReturn(77L);

        GraphChangedEvent event = service.requestReindex();

        verify(redis).delete(anyString());
        verify(eventPublisher).publish(any(GraphChangedEvent.class));
        assertEquals(77, event.nodeCount());
        assertEquals(GraphChangedEvent.TYPE_REINDEX, event.changeType());
    }

    private NeoTechNode neoNode(long id, String code, String name, boolean premium, String detail) {
        NeoTechNode n = new NeoTechNode();
        n.setId(id);
        n.setCode(code);
        n.setName(name);
        n.setEra("现代");
        n.setEraRank(5);
        n.setYearLabel("1900");
        n.setSummary("摘要");
        n.setDetail(detail);
        n.setPremium(premium);
        return n;
    }
}
