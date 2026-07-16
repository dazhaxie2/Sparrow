package com.sparrow.graph.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.graph.domain.model.FavoriteFolder;
import org.apache.ibatis.annotations.Mapper;

/** 一句话：用户收藏夹 Mapper。 */
@Mapper
public interface FavoriteFolderMapper extends BaseMapper<FavoriteFolder> {
}
