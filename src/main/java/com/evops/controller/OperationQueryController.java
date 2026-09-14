package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.OperationSearchRequest;
import com.evops.dto.TmStatsQuery;
import com.evops.service.OperationQueryService;
import com.evops.vo.ArticleFrameStatsVo;
import com.evops.vo.OperationSearchItemVo;
import com.evops.vo.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 航天器热真空试验与遥测判读运营检索。
 * 所有接口在 Shiro authcBasic 之后执行，Service 每次都解析租户与角色数据权限。
 */
@RestController
@RequestMapping("/api/tvac/operation")
public class OperationQueryController {

    private final OperationQueryService queryService;

    public OperationQueryController(OperationQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 组合筛选 + 分页运营检索（试验件/试验计划）。
     * 条件全部 AND 叠加，含日期与温压曲线范围；返回 records/total/稳定排序/游标。
     */
    @PostMapping("/search")
    public ApiResponse<PageResult<OperationSearchItemVo>> search(
            @Valid @RequestBody OperationSearchRequest req) {
        return ApiResponse.ok(queryService.search(req));
    }

    /**
     * 遥测帧按试验件分区聚合（30 万帧 / 1000 循环压测口径）。
     * 通道类条件半连接，帧数不被通道关联放大。
     */
    @GetMapping("/tm-stats")
    public ApiResponse<PageResult<ArticleFrameStatsVo>> frameStats(@Valid TmStatsQuery q) {
        return ApiResponse.ok(queryService.frameStats(q));
    }
}
