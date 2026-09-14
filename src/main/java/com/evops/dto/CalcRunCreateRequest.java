package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 区间计算请求：对某规则集在 [windowStartUtc, windowEndUtc)（设备 UTC，左闭右开）
 * 窗口内的遥测观测按任务时区归区间并累计。
 * 同一（规则版本 + 窗口）重复提交幂等返回原批次。
 */
@Data
public class CalcRunCreateRequest {
    @NotNull(message = "规则集ID不能为空")
    private Long setId;

    /** 指定规则版本；缺省取规则集当前启用版本 */
    private Long versionId;

    /** 窗口起点（设备 UTC，含） */
    @NotNull(message = "窗口起点不能为空")
    private LocalDateTime windowStartUtc;

    /** 窗口终点（设备 UTC，不含） */
    @NotNull(message = "窗口终点不能为空")
    private LocalDateTime windowEndUtc;
}
