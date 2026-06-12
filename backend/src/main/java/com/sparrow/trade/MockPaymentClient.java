package com.sparrow.trade;

import com.sparrow.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class MockPaymentClient implements PaymentClient {

    private final String mockSecret;

    public MockPaymentClient(@Value("${sparrow.pay.mock-secret:sparrow-phase1-mock-secret}") String mockSecret) {
        this.mockSecret = mockSecret;
    }

    @Override
    public String createPayment(Order order) {
        // 模拟收银台:前端打开该页面,点击"模拟支付成功"后由"网关"回调 /api/pay/mock/notify
        return "/pay.html?orderNo=" + order.getOrderNo() +
                "&amount=" + order.getAmountCent() +
                "&product=" + order.getProductCode() +
                "&payToken=" + urlEncode(tokenFor(order));
    }

    @Override
    public void verifyPaymentNotification(Order order, String payToken) {
        String expected = tokenFor(order);
        if (payToken == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                payToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(403, "支付回调校验失败");
        }
    }

    private String tokenFor(Order order) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(mockSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = order.getOrderNo() + ":" + order.getUserId() + ":" +
                    order.getProductCode() + ":" + order.getAmountCent();
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create mock payment token", e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
