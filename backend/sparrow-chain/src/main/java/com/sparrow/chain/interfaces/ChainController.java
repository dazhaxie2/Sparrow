package com.sparrow.chain.interfaces;

import com.sparrow.chain.application.ChainService;
import com.sparrow.chain.interfaces.dto.ChainDtos.ChainGraph;
import com.sparrow.chain.interfaces.dto.ChainDtos.ChainSummary;
import com.sparrow.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产业链专题接口。
 *
 * <p>路径前缀 /api/chains,经 sparrow-gateway 路由到本服务。
 * 与 /api/graph(科技树图谱)完全隔离,语义不同。</p>
 */
@RestController
@RequestMapping("/api/chains")
public class ChainController {

    private final ChainService chainService;

    public ChainController(ChainService chainService) {
        this.chainService = chainService;
    }

    /** 产业链列表(概览卡片)。 */
    @GetMapping
    public ApiResponse<List<ChainSummary>> list() {
        return ApiResponse.ok(chainService.listChains());
    }

    /** 单条产业链元数据。 */
    @GetMapping("/{slug}")
    public ApiResponse<ChainSummary> detail(@PathVariable String slug) {
        return ApiResponse.ok(chainService.chain(slug));
    }

    /** 单条产业链的网络图数据(节点 + 边),供前端 sigma 渲染。 */
    @GetMapping("/{slug}/graph")
    public ApiResponse<ChainGraph> graph(@PathVariable String slug) {
        return ApiResponse.ok(chainService.chainGraph(slug));
    }
}
