package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.RuleSetCreateRequest;
import com.evops.dto.RuleVersionCreateRequest;
import com.evops.entity.TvacRuleSet;
import com.evops.service.TvacRuleService;
import com.evops.vo.RuleVersionVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 时序判读规则集：按对象（试验件）维护，观测以设备 UTC 为源、按任务时区归区间。
 */
@RestController
@RequestMapping("/api/tvac/rule-sets")
public class TvacRuleController {

    private final TvacRuleService ruleService;

    public TvacRuleController(TvacRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /** 建档规则集（对象 + 任务时区） */
    @PostMapping
    public ApiResponse<TvacRuleSet> create(@Valid @RequestBody RuleSetCreateRequest req) {
        return ApiResponse.ok(ruleService.createSet(req));
    }

    @GetMapping
    public ApiResponse<List<TvacRuleSet>> list(
            @RequestParam(required = false) Long articleId) {
        return ApiResponse.ok(ruleService.listSets(articleId));
    }

    @GetMapping("/{id}")
    public ApiResponse<TvacRuleSet> get(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.getSet(id));
    }

    /** 新建规则版本（DRAFT）：版本号集内递增，区间随版本定义，重叠拒绝 */
    @PostMapping("/{setId}/versions")
    public ApiResponse<RuleVersionVo> createVersion(@PathVariable Long setId,
                                                    @Valid @RequestBody RuleVersionCreateRequest req) {
        return ApiResponse.ok(ruleService.createVersion(setId, req));
    }

    /** 版本列表（含各版区间；历史版本永久保留） */
    @GetMapping("/{setId}/versions")
    public ApiResponse<List<RuleVersionVo>> listVersions(@PathVariable Long setId) {
        return ApiResponse.ok(ruleService.listVersions(setId));
    }
}
