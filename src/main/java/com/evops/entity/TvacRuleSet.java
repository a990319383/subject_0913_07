package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 时序判读规则集：挂在对象（试验件）上。
 * 观测时间以设备 UTC 为源，按本集的任务时区（missionTz）归入业务区间。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_rule_set")
public class TvacRuleSet extends BaseEntity {
    /** 规则集编号（业务唯一键） */
    private String setCode;
    private String setName;
    private Long articleId;
    /** 任务时区（IANA ID，如 Asia/Shanghai），建档后不可改 */
    private String missionTz;
    private String status;
    private String remark;
}
