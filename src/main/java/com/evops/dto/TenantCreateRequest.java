package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class TenantCreateRequest {
    @NotBlank(message = "租户编码不能为空")
    private String tenantCode;
    @NotBlank(message = "租户名称不能为空")
    private String tenantName;
}
