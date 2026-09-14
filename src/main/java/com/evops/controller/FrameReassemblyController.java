package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.entity.TvacFramePiece;
import com.evops.entity.TvacFrameSession;
import com.evops.dto.FramePieceRequest;
import com.evops.dto.FrameSessionStartRequest;
import com.evops.service.FrameReassemblyService;
import com.evops.vo.FrameAssemblyVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 地面站二进制解码结果分片上报与重组：乱序到片、分片 CRC 校验、
 * 重复片幂等、单通道坏帧隔离。
 */
@RestController
@RequestMapping("/api/tvac/frame-sessions")
public class FrameReassemblyController {

    private final FrameReassemblyService reassemblyService;

    public FrameReassemblyController(FrameReassemblyService reassemblyService) {
        this.reassemblyService = reassemblyService;
    }

    /** 开始（或幂等复用）重组会话 */
    @PostMapping
    public ApiResponse<TvacFrameSession> start(@Valid @RequestBody FrameSessionStartRequest req) {
        return ApiResponse.ok(reassemblyService.startSession(req));
    }

    /** 上报一个通道分片（可乱序、可重复），收齐预期片数自动重组 */
    @PostMapping("/pieces")
    public ApiResponse<FrameAssemblyVo> receivePiece(@Valid @RequestBody FramePieceRequest req) {
        return ApiResponse.ok(reassemblyService.receivePiece(req));
    }

    /** 显式触发重组（坏片隔离，好片逐通道落遥测帧） */
    @PostMapping("/{sessionKey}/assemble")
    public ApiResponse<FrameAssemblyVo> assemble(@PathVariable String sessionKey) {
        return ApiResponse.ok(reassemblyService.assemble(sessionKey));
    }

    @GetMapping("/{sessionKey}")
    public ApiResponse<TvacFrameSession> get(@PathVariable String sessionKey) {
        return ApiResponse.ok(reassemblyService.getSession(sessionKey));
    }

    @GetMapping("/{sessionKey}/pieces")
    public ApiResponse<List<TvacFramePiece>> pieces(@PathVariable String sessionKey) {
        TvacFrameSession session = reassemblyService.getSession(sessionKey);
        return ApiResponse.ok(reassemblyService.listPieces(session.getId()));
    }
}
