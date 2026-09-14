package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.FrameCreateRequest;
import com.evops.entity.TvacTmFrame;
import com.evops.service.TvacTmFrameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/tvac/frames")
public class TvacTmFrameController {

    private final TvacTmFrameService frameService;

    public TvacTmFrameController(TvacTmFrameService frameService) {
        this.frameService = frameService;
    }

    /** 收单帧（自动按通道限界判越限标记） */
    @PostMapping
    public ApiResponse<TvacTmFrame> create(@Valid @RequestBody FrameCreateRequest req) {
        return ApiResponse.ok(frameService.create(req));
    }

    /** 批量收帧 */
    @PostMapping("/batch")
    public ApiResponse<List<TvacTmFrame>> createBatch(
            @Valid @RequestBody List<FrameCreateRequest> list) {
        return ApiResponse.ok(frameService.createBatch(list));
    }

    /** 按计划查询遥测帧（可按循环次、越限标记过滤） */
    @GetMapping
    public ApiResponse<List<TvacTmFrame>> list(
            @RequestParam Long planId,
            @RequestParam(required = false) Integer cycleNo,
            @RequestParam(required = false) String limitFlag) {
        return ApiResponse.ok(frameService.listByPlan(planId, cycleNo, limitFlag));
    }
}
