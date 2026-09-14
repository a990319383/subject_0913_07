package com.evops.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 遥测帧按试验件分区聚合的单行结果。
 * 直接在 t_tvac_tm_frame 上按 article_id GROUP BY，
 * 通道维度的任何过滤都走 EXISTS 半连接，通道关联不会放大帧数。
 */
@Data
public class ArticleFrameStatsVo {
    private Long articleId;
    private String articleCode;
    private String articleName;
    private Long tenantId;
    /** 去重后的计划数 */
    private Long planCount;
    /** 去重后的通道数 */
    private Long channelCount;
    /** 去重后的最大循环号（1000 循环压测口径） */
    private Integer maxCycleNo;
    /** 帧数（帧表行数，禁止被通道关联放大） */
    private Long totalFrames;
    private Long abnormalFrames;
    private BigDecimal maxEngValue;
    private BigDecimal minEngValue;
}
