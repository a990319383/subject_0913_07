package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 新建规则版本：版本号集内自动递增，初始为 DRAFT。
 * 区间随版本一并定义；版本启用后区间冻结，换版只能再建新版本。
 */
@Data
public class RuleVersionCreateRequest {
    private String remark;

    @Valid
    @NotEmpty(message = "规则版本至少需要一个业务区间")
    private List<RuleBandRequest> bands;
}
