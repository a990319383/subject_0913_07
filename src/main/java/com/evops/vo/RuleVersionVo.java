package com.evops.vo;

import com.evops.entity.TvacRuleBand;
import com.evops.entity.TvacRuleVersion;
import lombok.Data;

import java.util.List;

/**
 * 规则版本详情：版本头 + 业务区间列表
 */
@Data
public class RuleVersionVo {
    private TvacRuleVersion version;
    private List<TvacRuleBand> bands;
}
