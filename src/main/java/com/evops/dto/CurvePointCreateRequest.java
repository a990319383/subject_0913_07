package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CurvePointCreateRequest {
    @NotNull(message = "计划ID不能为空")
    private Long planId;

    @NotNull(message = "循环次序号不能为空")
    @Min(value = 1, message = "循环次序号从1开始")
    private Integer cycleNo;

    private LocalDateTime pointTime;

    @Min(value = 0, message = "偏移秒不能为负")
    private Integer offsetSec;

    @NotNull(message = "温度不能为空")
    private BigDecimal temperatureC;

    private BigDecimal pressurePa;
}
