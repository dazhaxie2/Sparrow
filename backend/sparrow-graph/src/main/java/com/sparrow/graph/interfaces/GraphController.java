package com.sparrow.graph.interfaces;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.security.UserContext;
import com.sparrow.graph.application.GraphService;
import com.sparrow.graph.interfaces.dto.GraphDtos.KnowledgeStatus;
import com.sparrow.graph.interfaces.dto.GraphDtos.Neighborhood;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodePage;
import com.sparrow.graph.interfaces.dto.GraphDtos.Overview;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/tree")
    public ApiResponse<Tree> tree() {
        return ApiResponse.ok(graphService.tree());
    }

    /** 维基级入口:领域×时代聚合总览(响应体小)。 */
    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok(graphService.overview());
    }

    /** 过滤 + 分页节点列表。 */
    @GetMapping("/nodes")
    public ApiResponse<NodePage> nodes(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer eraRank,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer minImportance,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "60") int size) {
        return ApiResponse.ok(graphService.nodes(category, eraRank, q, minImportance, page, size));
    }

    /** 名称/摘要检索。 */
    @GetMapping("/search")
    public ApiResponse<List<NodeBrief>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(graphService.search(q, limit));
    }

    /** 有界子图:过滤后取前 limit 重要节点 + 其间的边(默认/领域/时代视图渲染)。 */
    @GetMapping("/subgraph")
    public ApiResponse<Tree> subgraph(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer eraRank,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer minImportance,
            @RequestParam(defaultValue = "400") int limit) {
        return ApiResponse.ok(graphService.subgraph(category, eraRank, q, minImportance, limit));
    }

    /** 节点邻域子图(展开式浏览)。 */
    @GetMapping("/node/{id}/neighborhood")
    public ApiResponse<Neighborhood> neighborhood(@PathVariable Long id) {
        return ApiResponse.ok(graphService.neighborhood(id));
    }

    @GetMapping("/node/{id}")
    public ApiResponse<NodeDetail> node(@PathVariable Long id) {
        return ApiResponse.ok(graphService.nodeDetail(id, UserContext.get()));
    }

    @GetMapping("/node/{id}/prerequisites")
    public ApiResponse<List<NodeBrief>> prerequisites(@PathVariable Long id) {
        return ApiResponse.ok(graphService.prerequisiteChain(id));
    }

    @GetMapping("/knowledge/status")
    public ApiResponse<KnowledgeStatus> knowledgeStatus() {
        return ApiResponse.ok(graphService.knowledgeStatus());
    }
}
