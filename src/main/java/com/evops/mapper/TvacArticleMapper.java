package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.TvacArticle;
import com.evops.vo.ArticleBatchVo;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TvacArticleMapper extends BaseMapper<TvacArticle> {

    @Select("SELECT batch_no AS batchNo, MAX(target_model) AS targetModel, COUNT(*) AS articleCount "
            + "FROM t_tvac_article GROUP BY batch_no ORDER BY MIN(create_time)")
    List<ArticleBatchVo> listBatches();
}
