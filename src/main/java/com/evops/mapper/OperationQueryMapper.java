package com.evops.mapper;

import com.evops.dto.OperationSearchRequest;
import com.evops.dto.TmStatsQuery;
import com.evops.security.CurrentUser;
import com.evops.vo.ArticleFrameStatsVo;
import com.evops.vo.OperationSearchItemVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 运营检索 / 遥测分区聚合专用 Mapper。
 * 所有方法都强制接收 CurrentUser：租户与角色数据权限在每条 SQL 内下推，
 * 一对多关联一律 EXISTS 半连接或标量子查询，主记录行数不被放大。
 */
public interface OperationQueryMapper {

    long countArticles(@Param("cu") CurrentUser cu, @Param("q") OperationSearchRequest q);

    List<OperationSearchItemVo> pageArticles(@Param("cu") CurrentUser cu,
                                             @Param("q") OperationSearchRequest q,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    long countPlans(@Param("cu") CurrentUser cu, @Param("q") OperationSearchRequest q);

    List<OperationSearchItemVo> pagePlans(@Param("cu") CurrentUser cu,
                                          @Param("q") OperationSearchRequest q,
                                          @Param("limit") int limit,
                                          @Param("offset") long offset);

    long countFrameGroups(@Param("cu") CurrentUser cu, @Param("q") TmStatsQuery q);

    List<ArticleFrameStatsVo> pageFrameGroups(@Param("cu") CurrentUser cu,
                                              @Param("q") TmStatsQuery q,
                                              @Param("limit") int limit,
                                              @Param("offset") long offset);
}
