package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class TenantUserCreateRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "口令不能为空")
    private String password;
    private String realName;
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;
    /** TENANT_ADMIN / TENANT_VIEWER */
    @NotBlank(message = "角色不能为空")
    private String role;
}
