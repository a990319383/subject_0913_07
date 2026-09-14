package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 判读结论提交：结论与判读人为必填，统计字段由系统自动汇总。
 */
@Data
public class ReportJudgeRequest {
    @NotBlank(message = "判读结论不能为空")
    private String conclusion;

    @NotBlank(message = "判读人不能为空")
    private String judgedBy;

    private String remark;
}
