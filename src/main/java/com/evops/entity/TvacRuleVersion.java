package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 时序判读规则版本：同一规则集内版本号递增。
 * DRAFT 可改区间；启用后区间冻结、不能原地修改，换版只能新建版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_rule_version")
public class TvacRuleVersion extends BaseEntity {
    private Long setId;
    /** 集内递增版本号，从 1 开始 */
    private Integer versionNo;
    /** DRAFT / ENABLED / DISABLED */
    private String status;
    private LocalDateTime enabledTime;
    private String remark;
}
