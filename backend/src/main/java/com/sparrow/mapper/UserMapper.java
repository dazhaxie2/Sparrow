package com.sparrow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.entity.po.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
