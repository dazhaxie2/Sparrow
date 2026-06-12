package com.sparrow.trade.domain.port;

import com.sparrow.trade.domain.model.Order;

/**
 * 支付渠道抽象。Phase 1 用 Mock 渠道走通"下单→收银台→异步回调"全流程;
 * 接真实支付宝/微信沙箱时仅需新增实现类,业务代码不变。
 */
public interface PaymentClient {

    /** 创建支付单,返回收银台地址 */
    String createPayment(Order order);

    /** 校验支付渠道回调。Mock 渠道校验一次性支付令牌,真实渠道实现应验签/验金额。 */
    void verifyPaymentNotification(Order order, String payToken);
}
