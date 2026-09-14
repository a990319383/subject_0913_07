package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户账号：TENANT_ADMIN 可见本租户全部对象，TENANT_VIEWER 仅可见显式授权对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_user")
public class TvacUser extends BaseEntity {
    private String username;
    private String password;
    private String realName;
    private Long tenantId;
    /** SYSTEM / TENANT_ADMIN / TENANT_VIEWER */
    private String role;
    /** ACTIVE/DISABLED */
    private String status;
}
