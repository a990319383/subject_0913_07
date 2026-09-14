package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class ChannelCreateRequest {
    @NotBlank(message = "通道编号不能为空")
    private String channelCode;

    @NotBlank(message = "通道名称不能为空")
    private String channelName;

    @NotNull(message = "试验件ID不能为空")
    private Long articleId;

    @NotBlank(message = "测量量类型不能为空")
    private String measureType;

    private String unit;
    private BigDecimal upperLimit;
    private BigDecimal lowerLimit;
    private String remark;
}
