package com.evops.vo;

import lombok.Data;

/**
 * 按计划聚合的遥测帧统计（判读报告自动汇总用）
 */
@Data
public class PlanFrameStatsVo {
    /** 遥测帧记录到的最大循环号 */
    private Integer maxCycle;
    private Long totalFrames;
    private Long abnormalFrames;
}
