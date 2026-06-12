package com.sparrow.trade;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    List<Order> findTop20ByUserIdOrderByIdDesc(Long userId);

    /**
     * 幂等核销:仅 CREATED → PAID 才会更新成功(返回 1)。
     * 重复回调返回 0,调用方据此跳过会员开通,保证回调可重放。
     */
    @Modifying
    @Query("update Order o set o.status = 'PAID', o.paidAt = :paidAt " +
           "where o.orderNo = :orderNo and o.status = 'CREATED'")
    int markPaid(@Param("orderNo") String orderNo, @Param("paidAt") LocalDateTime paidAt);
}
