package com.sparrow.trade;

import com.sparrow.common.BizException;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockPaymentClientTest {

    private final MockPaymentClient client = new MockPaymentClient("test-secret");

    @Test
    void createPaymentIncludesVerifiableToken() {
        Order order = order();

        String payUrl = client.createPayment(order);
        String token = queryValue(payUrl, "payToken");

        assertTrue(payUrl.contains("orderNo=T202606120001"));
        assertDoesNotThrow(() -> client.verifyPaymentNotification(order, token));
    }

    @Test
    void rejectsForgedToken() {
        Order order = order();

        assertThrows(BizException.class,
                () -> client.verifyPaymentNotification(order, "forged-token"));
    }

    private Order order() {
        Order order = new Order();
        order.setOrderNo("T202606120001");
        order.setUserId(42L);
        order.setProductCode("MEMBER_MONTH");
        order.setProductName("Sparrow monthly member");
        order.setAmountCent(990);
        order.setStatus(Order.STATUS_CREATED);
        return order;
    }

    private String queryValue(String url, String key) {
        String marker = key + "=";
        int start = url.indexOf(marker);
        assertTrue(start >= 0, "missing " + key);
        start += marker.length();
        int end = url.indexOf('&', start);
        String encoded = end >= 0 ? url.substring(start, end) : url.substring(start);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}
