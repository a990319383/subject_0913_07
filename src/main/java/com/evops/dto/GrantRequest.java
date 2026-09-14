package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class GrantRequest {
    @NotNull(message = "账号ID不能为空")
    private Long userId;
    @NotNull(message = "试验件ID不能为空")
    private Long articleId;
}
