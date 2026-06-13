package com.sparrow.trade.application;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.event.OrderPaidEvent;
import com.sparrow.common.exception.BizException;
import com.sparrow.trade.domain.model.Order;
import com.sparrow.trade.domain.port.PaymentClient;
import com.sparrow.trade.infrastructure.client.UserClient;
import com.sparrow.trade.infrastructure.event.OrderPaidEventPublisher;
import com.sparrow.trade.infrastructure.persistence.OrderMapper;
import io.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    public record Product(String code, String name, int amountCent, int memberDays) {
    }

    public static final Map<String, Product> PRODUCTS = Map.of(
            "MEMBER_MONTH", new Product("MEMBER_MONTH", "Sparrow 月度会员", 990, 30),
            "MEMBER_YEAR", new Product("MEMBER_YEAR", "Sparrow 年度会员", 9900, 365)
    );

    private final OrderMapper orderMapper;
    private final PaymentClient paymentClient;
    private final UserClient userClient;
    private final OrderPaidEventPublisher eventPublisher;

    public TradeService(OrderMapper orderMapper, PaymentClient paymentClient, UserClient userClient,
                        OrderPaidEventPublisher eventPublisher) {
        this.orderMapper = orderMapper;
        this.paymentClient = paymentClient;
        this.userClient = userClient;
        this.eventPublisher = eventPublisher;
    }

    public record CreateOrderResult(String orderNo, String payUrl, int amountCent) {
    }

    @Transactional
    public CreateOrderResult createOrder(Long userId, String productCode) {
        Product product = PRODUCTS.get(productCode);
        if (product == null) {
            throw new BizException("商品不存在");
        }
        Order order = new Order();
        order.setOrderNo(genOrderNo());
        order.setUserId(userId);
        order.setProductCode(product.code());
        order.setProductName(product.name());
        order.setAmountCent(product.amountCent());
        order.setStatus(Order.STATUS_CREATED);
        orderMapper.insert(order);
        String payUrl = paymentClient.createPayment(order);
        return new CreateOrderResult(order.getOrderNo(), payUrl, order.getAmountCent());
    }

    /**
     * M2 阶段:trade 标记支付 + user 开通会员纳入 Seata AT 全局事务。
     * 若 user 返回业务错误或 Feign 调用异常,抛出异常触发全局回滚。
     */
    @GlobalTransactional(name = "sparrow-pay-notify", rollbackFor = Exception.class)
    @Transactional
    public boolean handlePayNotify(String orderNo, String payToken) {
        Order order = orderMapper.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在"));
        paymentClient.verifyPaymentNotification(order, payToken);
        LocalDateTime paidAt = LocalDateTime.now();
        int updated = orderMapper.markPaid(orderNo, paidAt);
        if (updated == 0) {
            log.info("重复支付回调,跳过: orderNo={}", orderNo);
            return false;
        }
        Product product = PRODUCTS.get(order.getProductCode());
        if (product == null) {
            throw new BizException(404, "商品不存在");
        }
        ApiResponse<Map<String, Object>> grantResp = userClient.grantMembership(
                order.getUserId(), new UserClient.GrantRequest(product.memberDays()));
        if (grantResp == null || grantResp.code() != 0) {
            String message = grantResp == null ? "会员开通无响应" : grantResp.message();
            throw new BizException(500, "会员开通失败:" + message);
        }
        eventPublisher.publishAfterCommit(new OrderPaidEvent(
                UUID.randomUUID().toString(),
                orderNo,
                order.getUserId(),
                product.code(),
                product.memberDays(),
                order.getAmountCent(),
                paidAt.atZone(ZoneId.systemDefault()).toInstant(),
                java.time.Instant.now()
        ));
        log.info("订单支付成功,会员已开通: orderNo={} userId={} days={}",
                orderNo, order.getUserId(), product.memberDays());
        return true;
    }

    public Order getOwnedOrder(Long userId, String orderNo) {
        Order order = orderMapper.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在"));
        if (!order.getUserId().equals(userId)) {
            throw new BizException(403, "无权查看该订单");
        }
        return order;
    }

    public List<Order> listOrders(Long userId) {
        return orderMapper.findTop20ByUserIdOrderByIdDesc(userId);
    }

    private String genOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(100000, 999999);
    }
}
