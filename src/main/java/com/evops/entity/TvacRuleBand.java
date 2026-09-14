package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 规则区间（业务区间）：任务时区当日分钟 [startMin, endMin) 左闭右开。
 * endMin &lt;= startMin 表示跨日（如 22:00 - 次日 10:00）。同版本内区间不允许重叠。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_rule_band")
public class TvacRuleBand extends BaseEntity {
    private Long versionId;
    /** PEAK 峰值 / FLAT 平段 / VALLEY 谷值 */
    private String bandType;
    /** 起始分钟（含），0..1439 */
    private Integer startMin;
    /** 结束分钟（不含），1..1440 */
    private Integer endMin;
}
