package com.sparrow.ai.infrastructure.client;

import com.sparrow.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "sparrow-graph", contextId = "aiGraphClient", path = "/internal/graph")
public interface GraphClient {

    @GetMapping("/tree")
    ApiResponse<GraphViews.Tree> tree();

    @GetMapping("/node/{id}")
    ApiResponse<GraphViews.NodeDetail> nodeDetail(@PathVariable("id") Long id,
                                                  @RequestParam(value = "userId", required = false) Long userId);

    @GetMapping("/node/{id}/prerequisites")
    ApiResponse<List<GraphViews.NodeBrief>> prerequisites(@PathVariable("id") Long id);

    @GetMapping("/indexable-nodes")
    ApiResponse<List<GraphViews.IndexableNode>> indexableNodes();
}
