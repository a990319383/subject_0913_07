package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 业务区间：任务时区当日分钟 [startMin, endMin) 左闭右开；
 * endMin &lt;= startMin 表示跨日（如 22:00 - 次日 10:00）。
 */
@Data
public class RuleBandRequest {
    /** PEAK 峰值 / FLAT 平段 / VALLEY 谷值 */
    @NotBlank(message = "区间类型不能为空")
    private String bandType;

    /** 起始分钟（含），0..1439 */
    @NotNull(message = "区间起始分钟不能为空")
    private Integer startMin;

    /** 结束分钟（不含），1..1440 */
    @NotNull(message = "区间结束分钟不能为空")
    private Integer endMin;
}
