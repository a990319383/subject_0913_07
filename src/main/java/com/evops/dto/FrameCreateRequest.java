package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FrameCreateRequest {
    @NotBlank(message = "帧序号不能为空")
    private String frameSeq;

    @NotNull(message = "计划ID不能为空")
    private Long planId;

    @NotNull(message = "通道ID不能为空")
    private Long channelId;

    @NotNull(message = "循环次序号不能为空")
    @Min(value = 1, message = "循环次序号从1开始")
    private Integer cycleNo;

    private LocalDateTime frameTime;

    private String rawValue;

    @NotNull(message = "工程值不能为空")
    private BigDecimal engValue;
}
