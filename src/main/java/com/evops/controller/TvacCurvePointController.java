package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.CurvePointCreateRequest;
import com.evops.entity.TvacCurvePoint;
import com.evops.service.TvacCurvePointService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/tvac/curve-points")
public class TvacCurvePointController {

    private final TvacCurvePointService curvePointService;

    public TvacCurvePointController(TvacCurvePointService curvePointService) {
        this.curvePointService = curvePointService;
    }

    /** 录入单个温压曲线点 */
    @PostMapping
    public ApiResponse<TvacCurvePoint> create(@Valid @RequestBody CurvePointCreateRequest req) {
        return ApiResponse.ok(curvePointService.create(req));
    }

    /** 批量录入温压曲线点 */
    @PostMapping("/batch")
    public ApiResponse<List<TvacCurvePoint>> createBatch(
            @Valid @RequestBody List<CurvePointCreateRequest> list) {
        return ApiResponse.ok(curvePointService.createBatch(list));
    }

    /** 按计划查询曲线（可按循环次过滤） */
    @GetMapping
    public ApiResponse<List<TvacCurvePoint>> list(
            @RequestParam Long planId,
            @RequestParam(required = false) Integer cycleNo) {
        return ApiResponse.ok(curvePointService.listByPlan(planId, cycleNo));
    }
}
