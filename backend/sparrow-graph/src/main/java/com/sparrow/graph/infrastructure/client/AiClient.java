package com.sparrow.graph.infrastructure.client;

import com.sparrow.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * sparrow-ai 服务 Feign 客户端(供 sparrow-graph 反向调用 AI 能力)。
 *
 * <p>与 {@code sparrow-ai→graph} 的 {@code GraphClient} 对称,复用 compose 网络内的 Feign 基建。
 * 当前用途:把材料节点的邻居交给 LLM 判定「应用/产业链」,结果缓存进 graph 库。</p>
 */
@FeignClient(name = "sparrow-ai", contextId = "graphAiClient", path = "/internal/ai")
public interface AiClient {

    /** 单个待判定邻居(只携带判定必需字段,减小跨服务报文)。 */
    record NeighborBrief(Long id, String name, String summary, String category) {
    }

    /** 应用判定请求。 */
    record ApplicationClassifyRequest(Long nodeId, String nodeName, String category,
                                      List<NeighborBrief> neighbors) {
    }

    /**
     * 判定哪些邻居是给定材料的下游应用/产业链。
     *
     * @param req 节点信息 + 邻居清单
     * @return 被判为应用的邻居 id 列表(可能为空);AI 未配置/异常时也返回空列表(降级)
     */
    @PostMapping("/applications")
    ApiResponse<List<Long>> classifyApplications(@RequestBody ApplicationClassifyRequest req);
}
