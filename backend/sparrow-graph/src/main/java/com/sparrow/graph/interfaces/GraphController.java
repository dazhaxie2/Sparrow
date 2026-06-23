package com.sparrow.graph.interfaces;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.security.UserContext;
import com.sparrow.graph.application.GraphService;
import com.sparrow.graph.interfaces.dto.GraphDtos.KnowledgeStatus;
import com.sparrow.graph.interfaces.dto.GraphDtos.Neighborhood;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeBrief;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodeDetail;
import com.sparrow.graph.interfaces.dto.GraphDtos.NodePage;
import com.sparrow.graph.interfaces.dto.GraphDtos.Tree;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    /**
     * 将 byte[] 直接写入 Servlet OutputStream,绕开 Spring 消息转换器。
     * 高并发下避免为 ~259KB/~380KB 的缓存 payload 创建中间 String/byte[] 副本。
     */
    private static ResponseEntity<StreamingResponseBody> streamJson(byte[] data) {
        StreamingResponseBody body = outputStream -> outputStream.write(data);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(data.length))
                .body(body);
    }

    /** 维基级入口:领域×时代聚合总览(响应体小,流式字节回写)。 */
    @GetMapping("/overview")
    public ResponseEntity<StreamingResponseBody> overview() {
        return streamJson(graphService.overviewBytes());
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

    /** 原始节点层:过滤后最多返回 1000 个重要节点 + 其间的边。 */
    @GetMapping("/subgraph")
    public ResponseEntity<StreamingResponseBody> subgraph(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer eraRank,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer minImportance,
            @RequestParam(defaultValue = "400") int limit) {
        return streamJson(graphService.subgraphBytes(category, eraRank, q, minImportance, limit));
    }

    /**
     * 百万级 LOD 瓦片:按层级 + 簇返回节点坐标 + 簇内边(流式字节回写)。
     * level 0 = 顶层簇全景(忽略 clusterId);level 1-3 = 指定簇内瓦片。
     * 前端按视口 zoom 级别请求对应 level,实现"远看簇/放大展开"的无限缩放。
     */
    @GetMapping("/tiles/{level}/{clusterId}")
    public ResponseEntity<StreamingResponseBody> tile(
            @PathVariable int level,
            @PathVariable long clusterId) {
        return streamJson(graphService.tileBytes(level, clusterId));
    }

    /** 社区聚簇总览：供 1001–10000 节点模式和 LOD 顶层使用。 */
    @GetMapping("/clusters")
    public ResponseEntity<StreamingResponseBody> clusters() {
        return streamJson(graphService.clusterOverviewBytes());
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
