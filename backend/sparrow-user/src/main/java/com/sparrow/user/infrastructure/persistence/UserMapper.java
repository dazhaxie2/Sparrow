package com.sparrow.user.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.user.domain.model.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
