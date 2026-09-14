package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 区间计算明细：每区间一行。
 * 累计值以 BigDecimal 全精度累加，均值仅在最终步骤统一舍入。
 * 区间字段冗余自计算采用的版本快照，规则换版后本行不变。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_calc_detail")
public class TvacCalcDetail extends BaseEntity {
    private Long runId;
    private Long bandId;
    /** PEAK / FLAT / VALLEY */
    private String bandType;
    private Integer startMin;
    private Integer endMin;
    private Integer obsCount;
    /** 累计和（全精度） */
    private BigDecimal sumValue;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    /** 均值（最终步骤统一舍入） */
    private BigDecimal avgValue;
}
