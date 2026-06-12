package com.sparrow.trade;

import com.sparrow.common.ApiResponse;
import com.sparrow.common.UserContext;
import com.sparrow.trade.TradeService.CreateOrderResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/trade")
@Validated
public class TradeController {

    public record CreateOrderRequest(@NotBlank String productCode) {
    }

    public record OrderView(String orderNo, String productCode, String productName,
                            int amountCent, String status, LocalDateTime createdAt, LocalDateTime paidAt) {

        static OrderView from(Order o) {
            return new OrderView(o.getOrderNo(), o.getProductCode(), o.getProductName(),
                    o.getAmountCent(), o.getStatus(), o.getCreatedAt(), o.getPaidAt());
        }
    }

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @GetMapping("/products")
    public ApiResponse<Collection<TradeService.Product>> products() {
        return ApiResponse.ok(TradeService.PRODUCTS.values());
    }

    @PostMapping("/order")
    public ApiResponse<CreateOrderResult> createOrder(@RequestBody @Validated CreateOrderRequest req) {
        return ApiResponse.ok(tradeService.createOrder(UserContext.require(), req.productCode()));
    }

    @GetMapping("/order/{orderNo}")
    public ApiResponse<OrderView> order(@PathVariable String orderNo) {
        return ApiResponse.ok(OrderView.from(tradeService.getOwnedOrder(UserContext.require(), orderNo)));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderView>> orders() {
        return ApiResponse.ok(tradeService.listOrders(UserContext.require()).stream()
                .map(OrderView::from).toList());
    }
}
