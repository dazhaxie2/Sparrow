package com.sparrow.graph.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.graph.domain.model.TechNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TechNodeMapper extends BaseMapper<TechNode> {
}
