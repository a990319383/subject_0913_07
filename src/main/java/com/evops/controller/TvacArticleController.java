package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ArticleBatchCreateRequest;
import com.evops.dto.ArticleCreateRequest;
import com.evops.dto.ArticleUpdateRequest;
import com.evops.dto.StatusChangeRequest;
import com.evops.entity.TvacArticle;
import com.evops.service.TvacArticleService;
import com.evops.vo.ArticleBatchVo;
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
@RequestMapping("/api/tvac/articles")
public class TvacArticleController {

    private final TvacArticleService articleService;

    public TvacArticleController(TvacArticleService articleService) {
        this.articleService = articleService;
    }

    /** 单件建档 */
    @PostMapping
    public ApiResponse<TvacArticle> create(@Valid @RequestBody ArticleCreateRequest req) {
        return ApiResponse.ok(articleService.create(req));
    }

    /** 批次建档 */
    @PostMapping("/batch")
    public ApiResponse<List<TvacArticle>> createBatch(
            @Valid @RequestBody ArticleBatchCreateRequest req) {
        return ApiResponse.ok(articleService.createBatch(req));
    }

    /** 试验件查询（可按批次号、状态过滤） */
    @GetMapping
    public ApiResponse<List<TvacArticle>> list(
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(articleService.list(batchNo, status));
    }

    /** 批次汇总 */
    @GetMapping("/batches")
    public ApiResponse<List<ArticleBatchVo>> listBatches() {
        return ApiResponse.ok(articleService.listBatches());
    }

    @GetMapping("/{id}")
    public ApiResponse<TvacArticle> get(@PathVariable Long id) {
        return ApiResponse.ok(articleService.getById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<TvacArticle> update(@PathVariable Long id,
                                           @RequestBody ArticleUpdateRequest req) {
        return ApiResponse.ok(articleService.update(id, req));
    }

    /** 状态流转：REGISTERED -> IN_TEST -> COMPLETED，或 -> SCRAPPED */
    @PutMapping("/{id}/status")
    public ApiResponse<TvacArticle> changeStatus(@PathVariable Long id,
                                                 @Valid @RequestBody StatusChangeRequest req) {
        return ApiResponse.ok(articleService.changeStatus(id, req.getStatus()));
    }

    /** 删除：已关联试验计划的试验件受保护 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        articleService.delete(id);
        return ApiResponse.ok(null);
    }
}
