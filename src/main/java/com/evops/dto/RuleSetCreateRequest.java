package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class RuleSetCreateRequest {
    @NotBlank(message = "规则集编号不能为空")
    private String setCode;

    @NotBlank(message = "规则集名称不能为空")
    private String setName;

    @NotNull(message = "对象（试验件）ID不能为空")
    private Long articleId;

    /** 任务时区（IANA ID，如 Asia/Shanghai）；观测以设备 UTC 为源，按此时区归区间 */
    @NotBlank(message = "任务时区不能为空")
    private String missionTz;

    private String remark;
}
