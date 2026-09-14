package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class ReportCreateRequest {
    @NotBlank(message = "报告编号不能为空")
    private String reportNo;

    @NotNull(message = "计划ID不能为空")
    private Long planId;

    private String remark;
}
