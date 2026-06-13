package com.sparrow.trade.application;

import com.sparrow.common.api.ApiResponse;
import com.sparrow.common.exception.BizException;
import com.sparrow.trade.domain.model.Order;
import com.sparrow.trade.domain.port.PaymentClient;
import com.sparrow.trade.infrastructure.client.UserClient;
import com.sparrow.trade.infrastructure.event.OrderPaidEventPublisher;
import com.sparrow.trade.infrastructure.persistence.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    private OrderMapper orderMapper;
    private PaymentClient paymentClient;
    private UserClient userClient;
    private OrderPaidEventPublisher eventPublisher;
    private TradeService tradeService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        paymentClient = mock(PaymentClient.class);
        userClient = mock(UserClient.class);
        eventPublisher = mock(OrderPaidEventPublisher.class);
        tradeService = new TradeService(orderMapper, paymentClient, userClient, eventPublisher);
    }

    @Test
    void payNotifyGrantsMembershipOnFirstValidCallback() {
        Order order = order();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderMapper.markPaid(eq(order.getOrderNo()), any(LocalDateTime.class))).thenReturn(1);
        when(userClient.grantMembership(eq(42L), eq(new UserClient.GrantRequest(30))))
                .thenReturn(ApiResponse.ok(null));

        boolean processed = tradeService.handlePayNotify(order.getOrderNo(), "valid-token");

        assertTrue(processed);
        verify(paymentClient).verifyPaymentNotification(order, "valid-token");
        verify(userClient).grantMembership(eq(42L), eq(new UserClient.GrantRequest(30)));
        verify(eventPublisher).publishAfterCommit(any());
    }

    @Test
    void payNotifySkipsDuplicateCallbacks() {
        Order order = order();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderMapper.markPaid(eq(order.getOrderNo()), any(LocalDateTime.class))).thenReturn(0);

        boolean processed = tradeService.handlePayNotify(order.getOrderNo(), "valid-token");

        assertFalse(processed);
        verify(paymentClient).verifyPaymentNotification(order, "valid-token");
        verify(userClient, never()).grantMembership(anyLong(), any(UserClient.GrantRequest.class));
        verify(eventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void payNotifyFailsWhenMembershipGrantReturnsBusinessError() {
        Order order = order();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderMapper.markPaid(eq(order.getOrderNo()), any(LocalDateTime.class))).thenReturn(1);
        when(userClient.grantMembership(eq(42L), eq(new UserClient.GrantRequest(30))))
                .thenReturn(ApiResponse.error(404, "用户不存在"));

        assertThrows(BizException.class,
                () -> tradeService.handlePayNotify(order.getOrderNo(), "valid-token"));

        verify(paymentClient).verifyPaymentNotification(order, "valid-token");
        verify(userClient).grantMembership(eq(42L), eq(new UserClient.GrantRequest(30)));
        verify(eventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void payNotifyRejectsForgedCallbacksBeforeMarkingPaid() {
        Order order = order();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        doThrow(new BizException(403, "支付回调校验失败"))
                .when(paymentClient).verifyPaymentNotification(order, "bad-token");

        assertThrows(BizException.class,
                () -> tradeService.handlePayNotify(order.getOrderNo(), "bad-token"));

        verify(orderMapper, never()).markPaid(any(), any());
        verify(userClient, never()).grantMembership(anyLong(), any(UserClient.GrantRequest.class));
        verify(eventPublisher, never()).publishAfterCommit(any());
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
}
