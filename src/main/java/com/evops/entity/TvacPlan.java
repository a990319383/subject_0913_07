package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 热真空试验计划
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_plan")
public class TvacPlan extends BaseEntity {
    /** 计划编号（业务唯一键） */
    private String planCode;
    private String planName;
    private Long articleId;
    /** 高温限（℃） */
    private BigDecimal highTempC;
    /** 低温限（℃） */
    private BigDecimal lowTempC;
    /** 真空度要求（Pa） */
    private BigDecimal vacuumPa;
    /** 目标循环次数 */
    private Integer targetCycles;
    private LocalDateTime planStartTime;
    private LocalDateTime planEndTime;
    /** 状态：DRAFT/ISSUED/RUNNING/COMPLETED/TERMINATED */
    private String status;
    private String remark;
}
