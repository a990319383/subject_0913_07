package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 判读报告：一份试验计划至多一份
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_report")
public class TvacReport extends BaseEntity {
    /** 报告编号（业务唯一键） */
    private String reportNo;
    private Long planId;
    /** 判读结论：PENDING/QUALIFIED/UNQUALIFIED/CONDITIONAL */
    private String conclusion;
    /** 实际循环次数（由曲线点/遥测帧自动统计） */
    private Integer actualCycles;
    private Integer totalFrames;
    private Integer abnormalFrames;
    private BigDecimal highTempReached;
    private BigDecimal lowTempReached;
    private BigDecimal minPressurePa;
    private String judgedBy;
    private LocalDateTime judgedTime;
    private String acceptedBy;
    private LocalDateTime acceptedTime;
    private LocalDateTime postedTime;
    private String remark;
}
