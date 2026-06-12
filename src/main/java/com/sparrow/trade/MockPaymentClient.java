package com.sparrow.trade;

import org.springframework.stereotype.Component;

@Component
public class MockPaymentClient implements PaymentClient {

    @Override
    public String createPayment(Order order) {
        // 模拟收银台:前端打开该页面,点击"模拟支付成功"后由"网关"回调 /api/pay/mock/notify
        return "/pay.html?orderNo=" + order.getOrderNo() +
                "&amount=" + order.getAmountCent() +
                "&product=" + order.getProductCode();
    }
}
