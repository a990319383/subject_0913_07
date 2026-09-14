package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PlanCreateRequest;
import com.evops.dto.PlanUpdateRequest;
import com.evops.dto.StatusChangeRequest;
import com.evops.entity.TvacPlan;
import com.evops.service.TvacPlanService;
import com.evops.vo.PlanDetailVo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/tvac/plans")
public class TvacPlanController {

    private final TvacPlanService planService;

    public TvacPlanController(TvacPlanService planService) {
        this.planService = planService;
    }

    @PostMapping
    public ApiResponse<TvacPlan> create(@Valid @RequestBody PlanCreateRequest req) {
        return ApiResponse.ok(planService.create(req));
    }

    /** 编辑（仅 DRAFT 可改） */
    @PutMapping("/{id}")
    public ApiResponse<TvacPlan> update(@PathVariable Long id,
                                        @Valid @RequestBody PlanUpdateRequest req) {
        return ApiResponse.ok(planService.update(id, req));
    }

    @GetMapping
    public ApiResponse<List<TvacPlan>> list(
            @RequestParam(required = false) Long articleId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(planService.list(articleId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<TvacPlan> get(@PathVariable Long id) {
        return ApiResponse.ok(planService.getById(id));
    }

    /** 关联查询：计划 + 试验件 + 通道 + 判读报告 */
    @GetMapping("/{id}/detail")
    public ApiResponse<PlanDetailVo> detail(@PathVariable Long id) {
        return ApiResponse.ok(planService.detail(id));
    }

    /** 状态流转：DRAFT -> ISSUED -> RUNNING -> COMPLETED/TERMINATED */
    @PutMapping("/{id}/status")
    public ApiResponse<TvacPlan> changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody StatusChangeRequest req) {
        return ApiResponse.ok(planService.changeStatus(id, req.getStatus()));
    }

    /** 删除：非草稿或已有试验数据/报告的计划受保护 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        planService.delete(id);
        return ApiResponse.ok(null);
    }
}
