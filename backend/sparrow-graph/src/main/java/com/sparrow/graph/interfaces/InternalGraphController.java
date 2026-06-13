package com.sparrow.graph.interfaces;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.GraphChangedEvent;
import com.sparrow.graph.application.GraphService;
import com.sparrow.graph.interfaces.dto.GraphDtos.IndexableNode;
import com.sparrow.graph.interfaces.dto.GraphDtos.KnowledgeStatus;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仅供 compose 网络内其他服务通过 Feign 调用。
 * M3 起新增 /reindex 事件触发端点,M4 起新增 /import。
 */
@RestController
@RequestMapping("/internal/graph")
public class InternalGraphController {

    private final GraphService graphService;

    public InternalGraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/tree")
    public ApiResponse<Tree> tree() {
        return ApiResponse.ok(graphService.tree());
    }

    @GetMapping("/node/{id}")
    public ApiResponse<NodeDetail> node(@PathVariable Long id,
                                        @RequestParam(value = "userId", required = false) Long userId) {
        return ApiResponse.ok(graphService.nodeDetail(id, userId));
    }

    @GetMapping("/node/{id}/prerequisites")
    public ApiResponse<List<NodeBrief>> prerequisites(@PathVariable Long id) {
        return ApiResponse.ok(graphService.prerequisiteChain(id));
    }

    @GetMapping("/indexable-nodes")
    public ApiResponse<List<IndexableNode>> indexableNodes() {
        return ApiResponse.ok(graphService.listIndexableNodes());
    }

    @GetMapping("/knowledge/status")
    public ApiResponse<KnowledgeStatus> knowledgeStatus() {
        return ApiResponse.ok(graphService.knowledgeStatus());
    }

    @PostMapping("/reindex")
    public ApiResponse<GraphChangedEvent> reindex() {
        return ApiResponse.ok(graphService.requestReindex());
    }

    @PostMapping("/import")
    public ApiResponse<GraphChangedEvent> importFromMysql() {
        return ApiResponse.ok(graphService.importFromMysqlAndReindex());
    }
}
