package com.evops.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 通道编辑：限界调整后，后续录入的遥测帧按新限界判读。
 */
@Data
public class ChannelUpdateRequest {
    private String channelName;
    private String unit;
    private BigDecimal upperLimit;
    private BigDecimal lowerLimit;
    private String remark;
}
