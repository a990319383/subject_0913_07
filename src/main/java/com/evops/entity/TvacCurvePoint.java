package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 温压曲线点：试验件在某循环某时刻的温度/真空度采样
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_curve_point")
public class TvacCurvePoint extends BaseEntity {
    private Long planId;
    private Long articleId;
    /** 循环次序号 */
    private Integer cycleNo;
    /** 采样时刻 */
    private LocalDateTime pointTime;
    /** 相对试验起始的偏移秒 */
    private Integer offsetSec;
    /** 温度（℃） */
    private BigDecimal temperatureC;
    /** 真空度（Pa） */
    private BigDecimal pressurePa;
}
