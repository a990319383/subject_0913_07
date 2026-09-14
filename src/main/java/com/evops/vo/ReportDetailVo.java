package com.evops.vo;

import com.evops.entity.TvacPlan;
import com.evops.entity.TvacReport;
import lombok.Data;

/**
 * 判读报告详情：报告 + 计划 + 试验件编号
 */
@Data
public class ReportDetailVo {
    private TvacReport report;
    private TvacPlan plan;
    private Long articleId;
    private String articleCode;
    private String articleName;
}
