package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计划编辑：仅 DRAFT 状态允许修改。
 */
@Data
public class PlanUpdateRequest {
    private String planName;
    private BigDecimal highTempC;
    private BigDecimal lowTempC;
    private BigDecimal vacuumPa;

    @Min(value = 0, message = "目标循环次数不能为负")
    private Integer targetCycles;

    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    private String remark;
}
