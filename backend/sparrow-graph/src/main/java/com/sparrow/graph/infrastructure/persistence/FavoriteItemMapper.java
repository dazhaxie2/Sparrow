package com.sparrow.graph.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.graph.domain.model.FavoriteItem;
import org.apache.ibatis.annotations.Mapper;

/** 一句话：用户收藏节点 Mapper。 */
@Mapper
public interface FavoriteItemMapper extends BaseMapper<FavoriteItem> {
}
