package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 判读报告绑定区间计算批次：绑定后报告随判读引用该批次的规则版本快照；
 * 报告验收/落账（签发）后绑定锁定，规则换版不污染已签发报告。
 */
@Data
public class ReportBindCalcRequest {
    @NotNull(message = "计算批次ID不能为空")
    private Long runId;
}
