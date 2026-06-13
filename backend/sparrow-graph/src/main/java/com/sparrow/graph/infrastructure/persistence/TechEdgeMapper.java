package com.sparrow.graph.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.graph.domain.model.TechEdge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TechEdgeMapper extends BaseMapper<TechEdge> {
}
