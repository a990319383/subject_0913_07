package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.CalcRunCreateRequest;
import com.evops.entity.TvacCalcRun;
import com.evops.service.TvacCalcService;
import com.evops.vo.CalcRunDetailVo;
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
 * 区间计算批次：按（规则版本 + UTC 窗口）幂等执行；
 * 历史批次读取当时采用的规则快照，规则换版不污染。
 */
@RestController
@RequestMapping("/api/tvac/calc-runs")
public class TvacCalcController {

    private final TvacCalcService calcService;

    public TvacCalcController(TvacCalcService calcService) {
        this.calcService = calcService;
    }

    /** 执行区间计算（幂等）：同范围重复提交/并发重算只产生一份结果 */
    @PostMapping
    public ApiResponse<CalcRunDetailVo> execute(@Valid @RequestBody CalcRunCreateRequest req) {
        return ApiResponse.ok(calcService.execute(req));
    }

    /** 批次详情：运行头 + 逐区间明细，窗口按任务时区展示 */
    @GetMapping("/{id}")
    public ApiResponse<CalcRunDetailVo> detail(@PathVariable Long id) {
        return ApiResponse.ok(calcService.detail(id));
    }

    @GetMapping
    public ApiResponse<List<TvacCalcRun>> list(
            @RequestParam(required = false) Long setId,
            @RequestParam(required = false) Long articleId) {
        return ApiResponse.ok(calcService.list(setId, articleId));
    }
}
