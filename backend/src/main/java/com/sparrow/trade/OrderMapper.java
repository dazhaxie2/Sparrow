package com.sparrow.trade;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo}")
    Optional<Order> findByOrderNo(@Param("orderNo") String orderNo);

    @Update("UPDATE t_order SET status = 'PAID', paid_at = #{paidAt} WHERE order_no = #{orderNo} AND status = 'CREATED'")
    int markPaid(@Param("orderNo") String orderNo, @Param("paidAt") LocalDateTime paidAt);

    @Select("SELECT * FROM t_order WHERE user_id = #{userId} ORDER BY id DESC LIMIT 20")
    List<Order> findTop20ByUserIdOrderByIdDesc(@Param("userId") Long userId);
}
