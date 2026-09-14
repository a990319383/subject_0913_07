package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.RuleBandRequest;
import com.evops.entity.TvacRuleVersion;
import com.evops.service.TvacRuleService;
import com.evops.vo.RuleVersionVo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 规则版本操作：启用/停用/草稿期换区间。
 * 启用后区间冻结、不能原地修改；换版只能新建版本，历史版本永久保留。
 */
@RestController
@RequestMapping("/api/tvac/rule-versions")
public class TvacRuleVersionController {

    private final TvacRuleService ruleService;

    public TvacRuleVersionController(TvacRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping("/{id}")
    public ApiResponse<RuleVersionVo> get(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.getVersion(id));
    }

    /** 启用：同规则集其他启用版自动停用 */
    @PutMapping("/{id}/enable")
    public ApiResponse<TvacRuleVersion> enable(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.enable(id));
    }

    /** 停用：仅启用中版本可停用，停用版区间仍冻结 */
    @PutMapping("/{id}/disable")
    public ApiResponse<TvacRuleVersion> disable(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.disable(id));
    }

    /** 整体替换区间：仅 DRAFT 允许；启用后不能原地修改 */
    @PutMapping("/{id}/bands")
    public ApiResponse<RuleVersionVo> replaceBands(
            @PathVariable Long id,
            @Valid @RequestBody BandsBody body) {
        return ApiResponse.ok(ruleService.replaceBands(id, body.getBands()));
    }

    /** 删除：仅 DRAFT；已启用过的版本作为历史档案保留 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ruleService.deleteVersion(id);
        return ApiResponse.ok(null);
    }

    /** 换区间请求体 */
    public static class BandsBody {
        @Valid
        @NotEmpty(message = "规则版本至少需要一个业务区间")
        private List<RuleBandRequest> bands;

        public List<RuleBandRequest> getBands() {
            return bands;
        }

        public void setBands(List<RuleBandRequest> bands) {
            this.bands = bands;
        }
    }
}
