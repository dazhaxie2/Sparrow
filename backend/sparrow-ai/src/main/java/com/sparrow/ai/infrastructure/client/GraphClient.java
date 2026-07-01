package com.sparrow.ai.infrastructure.client;

import com.sparrow.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 图谱服务 Feign 客户端。
 * 调用 sparrow-graph 服务获取科技图数据。
 */
@FeignClient(name = "sparrow-graph", contextId = "aiGraphClient", path = "/internal/graph")
public interface GraphClient {

    /**
     * 获取完整科技图结构。
     *
     * @return 包含节点和边的科技图
     */
    @GetMapping("/tree")
    ApiResponse<GraphViews.Tree> tree();

    /**
     * 获取节点详细信息。
     *
     * @param id     节点 ID
     * @param userId 用户 ID(可选,用于权限判断)
     * @return 节点详情
     */
    @GetMapping("/node/{id}")
    ApiResponse<GraphViews.NodeDetail> nodeDetail(@PathVariable("id") Long id,
                                                  @RequestParam(value = "userId", required = false) Long userId);

    /**
     * 获取节点的完整前置依赖链。
     *
     * @param id 节点 ID
     * @return 前置技术节点列表
     */
    @GetMapping("/node/{id}/prerequisites")
    ApiResponse<List<GraphViews.NodeBrief>> prerequisites(@PathVariable("id") Long id);

    /**
     * 获取可索引的节点列表(用于 RAG 向量化)。
     *
     * @return 可索引节点列表
     */
    @GetMapping("/indexable-nodes")
    ApiResponse<List<GraphViews.IndexableNode>> indexableNodes();
}
