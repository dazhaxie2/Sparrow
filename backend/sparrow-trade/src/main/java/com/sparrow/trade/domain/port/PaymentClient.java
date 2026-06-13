package com.sparrow.trade.domain.port;

import com.sparrow.trade.domain.model.Order;

/**
 * 支付渠道抽象。Mock 渠道走通"下单→收银台→异步回调"全流程;
 * 接真实支付宝/微信沙箱时仅需新增实现类,业务代码不变。
 */
public interface PaymentClient {

    String createPayment(Order order);

    void verifyPaymentNotification(Order order, String payToken);
}
