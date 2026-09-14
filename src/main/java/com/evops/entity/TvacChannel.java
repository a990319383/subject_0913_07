package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 遥测通道
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_channel")
public class TvacChannel extends BaseEntity {
    /** 通道编号（业务唯一键） */
    private String channelCode;
    private String channelName;
    private Long articleId;
    /** 测量量类型：TEMPERATURE/PRESSURE/VOLTAGE/CURRENT/OTHER */
    private String measureType;
    private String unit;
    /** 工程值上限 */
    private BigDecimal upperLimit;
    /** 工程值下限 */
    private BigDecimal lowerLimit;
    /** 状态：ENABLED/DISABLED */
    private String status;
    private String remark;
}
