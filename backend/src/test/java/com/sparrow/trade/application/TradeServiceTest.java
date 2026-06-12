package com.sparrow.trade.application;

import com.sparrow.trade.domain.model.Order;
import com.sparrow.common.exception.BizException;
import com.sparrow.trade.infrastructure.persistence.OrderMapper;
import com.sparrow.user.api.MembershipGrantService;
import com.sparrow.trade.domain.port.PaymentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private MembershipGrantService membershipGrantService;
    private TradeService tradeService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        paymentClient = mock(PaymentClient.class);
        membershipGrantService = mock(MembershipGrantService.class);
        tradeService = new TradeService(orderMapper, paymentClient, membershipGrantService);
    }

    @Test
    void payNotifyGrantsMembershipOnFirstValidCallback() {
        Order order = order();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderMapper.markPaid(eq(order.getOrderNo()), any(LocalDateTime.class))).thenReturn(1);

        boolean processed = tradeService.handlePayNotify(order.getOrderNo(), "valid-token");

        assertTrue(processed);
        verify(paymentClient).verifyPaymentNotification(order, "valid-token");
        verify(membershipGrantService).grantMembership(42L, 30);
    }

    @Test
    void payNotifySkipsDuplicateCallbacks() {
        Order order = order();
        when(orderMapper.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        when(orderMapper.markPaid(eq(order.getOrderNo()), any(LocalDateTime.class))).thenReturn(0);

        boolean processed = tradeService.handlePayNotify(order.getOrderNo(), "valid-token");

        assertFalse(processed);
        verify(paymentClient).verifyPaymentNotification(order, "valid-token");
        verify(membershipGrantService, never()).grantMembership(anyLong(), anyInt());
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
        verify(membershipGrantService, never()).grantMembership(anyLong(), anyInt());
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
