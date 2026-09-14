package com.evops.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 按计划聚合的温压曲线统计（判读报告自动汇总用）
 */
@Data
public class PlanCurveStatsVo {
    /** 曲线点记录到的最大循环号 */
    private Integer maxCycle;
    /** 曲线点数量 */
    private Long curvePoints;
    private BigDecimal highTempReached;
    private BigDecimal lowTempReached;
    private BigDecimal minPressurePa;
}
