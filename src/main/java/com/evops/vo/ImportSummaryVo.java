package com.evops.vo;

import lombok.Data;

import java.util.List;

/**
 * CSV 批量导入结果汇总 + 逐行明细。
 */
@Data
public class ImportSummaryVo {
    private Long batchId;
    private String batchNo;
    private String fileName;
    private String fileChecksum;
    /** PROCESSING（存在失败分片）/ COMPLETED（全部分片处理完，允许逐行失败） */
    private String status;
    private boolean idempotentHit;
    private int shardSize;
    private int shardCount;
    private int totalRows;
    private int successCount;
    private int updatedCount;
    private int failedCount;
    private List<ImportShardVo> shards;
    /** 逐行结果：SUCCESS / UPDATED / FAILED 全部返回 */
    private List<ImportRowResultVo> rows;
    /** 失败行的字段级错误明细（原始行号、字段、原值、原因） */
    private List<ImportErrorVo> errors;
}
