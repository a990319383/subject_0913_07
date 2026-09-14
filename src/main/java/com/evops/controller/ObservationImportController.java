package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.service.ObservationImportService;
import com.evops.vo.ImportSummaryVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 航天器热真空试验与遥测判读观测数据 CSV 批量导入。
 *
 * <p>逐行返回成功（SUCCESS）/ 更新（UPDATED）/ 失败（FAILED）明细；
 * 文件 SHA-256 + 业务键双重幂等；分片独立事务、失败分片可重试。
 */
@RestController
@RequestMapping("/api/tvac/observations")
public class ObservationImportController {

    private final ObservationImportService importService;

    public ObservationImportController(ObservationImportService importService) {
        this.importService = importService;
    }

    /**
     * 上传 CSV 批量导入。
     *
     * @param shardSize 分片行数，默认 5000（50,000 行切 10 片）
     */
    @PostMapping("/import")
    public ApiResponse<ImportSummaryVo> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "shardSize", required = false) Integer shardSize) throws IOException {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("上传文件为空");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        String fileName = file.getOriginalFilename();
        return ApiResponse.ok(importService.importCsv(content, fileName, shardSize));
    }

    /** 直接以 CSV 文本导入（便于地面站系统对接/测试） */
    @PostMapping("/import-text")
    public ApiResponse<ImportSummaryVo> importText(
            @RequestParam("content") String content,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "shardSize", required = false) Integer shardSize) {
        return ApiResponse.ok(importService.importCsv(content, fileName, shardSize));
    }

    /** 查询导入批次结果（含逐行与错误明细） */
    @GetMapping("/batches/{batchId}")
    public ApiResponse<ImportSummaryVo> getBatch(@PathVariable Long batchId) {
        return ApiResponse.ok(importService.getSummary(batchId));
    }

    /** 重试系统失败分片（FAILED/PENDING），业务键幂等，不重复落数据 */
    @PostMapping("/batches/{batchId}/shards/{shardNo}/retry")
    public ApiResponse<ImportSummaryVo> retryShard(@PathVariable Long batchId,
                                                   @PathVariable Integer shardNo) {
        return ApiResponse.ok(importService.retryShard(batchId, shardNo));
    }
}
