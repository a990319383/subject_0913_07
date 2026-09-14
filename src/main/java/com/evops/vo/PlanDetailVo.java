package com.evops.vo;

import com.evops.entity.TvacArticle;
import com.evops.entity.TvacChannel;
import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import lombok.Data;

import java.util.List;

/**
 * 试验计划详情：计划本体 + 试验件 + 遥测通道 + 判读报告
 */
@Data
public class PlanDetailVo {
    private TvacPlan plan;
    private TvacArticle article;
    private List<TvacChannel> channels;
    private TvacReport report;
}
