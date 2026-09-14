package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PlanCreateRequest {
    @NotBlank(message = "计划编号不能为空")
    private String planCode;

    @NotBlank(message = "计划名称不能为空")
    private String planName;

    @NotNull(message = "试验件ID不能为空")
    private Long articleId;

    @NotNull(message = "高温限不能为空")
    private BigDecimal highTempC;

    @NotNull(message = "低温限不能为空")
    private BigDecimal lowTempC;

    private BigDecimal vacuumPa;

    @Min(value = 0, message = "目标循环次数不能为负")
    private Integer targetCycles = 0;

    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    private String remark;
}
