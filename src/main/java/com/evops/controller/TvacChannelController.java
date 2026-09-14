package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ChannelCreateRequest;
import com.evops.dto.ChannelUpdateRequest;
import com.evops.dto.StatusChangeRequest;
import com.evops.entity.TvacChannel;
import com.evops.service.TvacChannelService;
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
@RequestMapping("/api/tvac/channels")
public class TvacChannelController {

    private final TvacChannelService channelService;

    public TvacChannelController(TvacChannelService channelService) {
        this.channelService = channelService;
    }

    @PostMapping
    public ApiResponse<TvacChannel> create(@Valid @RequestBody ChannelCreateRequest req) {
        return ApiResponse.ok(channelService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<TvacChannel> update(@PathVariable Long id,
                                           @Valid @RequestBody ChannelUpdateRequest req) {
        return ApiResponse.ok(channelService.update(id, req));
    }

    @GetMapping
    public ApiResponse<List<TvacChannel>> list(
            @RequestParam(required = false) Long articleId,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(channelService.list(articleId, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<TvacChannel> get(@PathVariable Long id) {
        return ApiResponse.ok(channelService.getById(id));
    }

    /** 启停用：ENABLED <-> DISABLED */
    @PutMapping("/{id}/status")
    public ApiResponse<TvacChannel> changeStatus(@PathVariable Long id,
                                                 @Valid @RequestBody StatusChangeRequest req) {
        return ApiResponse.ok(channelService.changeStatus(id, req.getStatus()));
    }

    /** 删除：已录入遥测帧的通道受保护 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        channelService.delete(id);
        return ApiResponse.ok(null);
    }
}
