package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ReportAcceptRequest {
    @NotBlank(message = "验收人不能为空")
    private String acceptedBy;

    private String remark;
}
