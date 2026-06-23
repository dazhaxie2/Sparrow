package com.sparrow.graph.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sparrow.graph.domain.model.TechNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TechNodeMapper extends BaseMapper<TechNode> {

    /** 名称匹配优先于摘要匹配，同级按重要度降序。 */
    @Select("""
            SELECT id, code, name, era, era_rank, year_label, summary, detail,
                   premium, category, importance
            FROM tech_node
            WHERE name LIKE CONCAT('%', #{q}, '%')
               OR summary LIKE CONCAT('%', #{q}, '%')
            ORDER BY CASE
                       WHEN name = #{q} THEN 0
                       WHEN name LIKE CONCAT(#{q}, '%') THEN 1
                       WHEN name LIKE CONCAT('%', #{q}, '%') THEN 2
                       ELSE 3
                     END,
                     importance DESC,
                     id ASC
            LIMIT #{limit}
            """)
    List<TechNode> searchRelevant(@Param("q") String q, @Param("limit") int limit);
}
