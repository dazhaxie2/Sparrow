package com.sparrow.graph;

import com.sparrow.common.ApiResponse;
import com.sparrow.common.UserContext;
import com.sparrow.graph.GraphDtos.NodeBrief;
import com.sparrow.graph.GraphDtos.NodeDetail;
import com.sparrow.graph.GraphDtos.Tree;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/node/{id}")
    public ApiResponse<NodeDetail> node(@PathVariable Long id) {
        return ApiResponse.ok(graphService.nodeDetail(id, UserContext.get()));
    }

    @GetMapping("/node/{id}/prerequisites")
    public ApiResponse<List<NodeBrief>> prerequisites(@PathVariable Long id) {
        return ApiResponse.ok(graphService.prerequisiteChain(id));
    }
}
