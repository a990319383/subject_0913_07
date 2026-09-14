package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * 遥测分区聚合查询条件：按试验件分区聚合帧统计。
 * 通道过滤（channelId/measureType）在 SQL 中以 EXISTS 半连接实现，不放大帧数。
 */
@Data
public class TmStatsQuery {
    private Long articleId;
    private String articleCode;
    private String batchNo;
    private Long planId;
    private Long channelId;
    /** 按通道测量类型过滤（TEMPERATURE/PRESSURE/...），半连接通道表 */
    private String measureType;
    private String limitFlag;
    private Integer cycleFrom;
    private Integer cycleTo;

    @Min(value = 1, message = "页码从1开始")
    private Integer pageNum = 1;

    @Min(value = 1, message = "pageSize 只能为 1-100")
    @Max(value = 100, message = "pageSize 只能为 1-100")
    private Integer pageSize = 20;
}
