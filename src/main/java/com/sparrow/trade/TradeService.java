package com.sparrow.trade;

import com.sparrow.common.BizException;
import com.sparrow.common.MembershipGrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
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
    private final MembershipGrantService membershipGrantService;

    public TradeService(OrderMapper orderMapper, PaymentClient paymentClient,
                        MembershipGrantService membershipGrantService) {
        this.orderMapper = orderMapper;
        this.paymentClient = paymentClient;
        this.membershipGrantService = membershipGrantService;
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

    @Transactional
    public boolean handlePayNotify(String orderNo, String payToken) {
        Order order = orderMapper.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在"));
        paymentClient.verifyPaymentNotification(order, payToken);
        int updated = orderMapper.markPaid(orderNo, LocalDateTime.now());
        if (updated == 0) {
            log.info("重复支付回调,跳过: orderNo={}", orderNo);
            return false;
        }
        Product product = PRODUCTS.get(order.getProductCode());
        if (product == null) {
            throw new BizException(404, "商品不存在");
        }
        membershipGrantService.grantMembership(order.getUserId(), product.memberDays());
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
