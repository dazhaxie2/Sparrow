package com.sparrow.trade;

import com.sparrow.common.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟支付网关的异步回调入口。
 * 真实接入支付宝沙箱时,该路径由支付宝服务端回调(需验签);Mock 模式由收银台页面触发。
 */
@RestController
@RequestMapping("/api/pay/mock")
@Validated
public class MockPayController {

    public record NotifyRequest(@NotBlank String orderNo) {
    }

    private final TradeService tradeService;

    public MockPayController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping("/notify")
    public ApiResponse<Map<String, Object>> notify(@RequestBody @Validated NotifyRequest req) {
        boolean firstTime = tradeService.handlePayNotify(req.orderNo());
        return ApiResponse.ok(Map.of("orderNo", req.orderNo(), "processed", firstTime));
    }
}
