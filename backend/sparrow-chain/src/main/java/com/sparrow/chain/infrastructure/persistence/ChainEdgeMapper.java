package com.sparrow.chain.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.chain.domain.model.ChainEdge;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChainEdgeMapper extends BaseMapper<ChainEdge> {
}
