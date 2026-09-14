package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户（运营单位）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_tenant")
public class TvacTenant extends BaseEntity {
    private String tenantCode;
    private String tenantName;
    /** ACTIVE/DISABLED */
    private String status;
}
