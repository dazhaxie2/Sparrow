package com.sparrow.chain.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.chain.domain.model.ChainNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChainNodeMapper extends BaseMapper<ChainNode> {
}
