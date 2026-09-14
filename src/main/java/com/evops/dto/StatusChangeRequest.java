package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class StatusChangeRequest {
    /** 目标状态或流转动作，由各 Service 校验合法取值与流转关系 */
    @NotBlank(message = "目标状态不能为空")
    private String status;
}
