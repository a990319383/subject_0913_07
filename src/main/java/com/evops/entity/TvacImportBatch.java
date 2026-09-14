package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 观测数据 CSV 导入批次：文件级 SHA-256 校验和唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_import_batch")
public class TvacImportBatch extends BaseEntity {
    private String batchNo;
    private String fileName;
    private String fileChecksum;
    private Integer totalRows;
    private Integer successCount;
    private Integer updatedCount;
    private Integer failedCount;
    private Integer shardSize;
    private Integer shardCount;
    /** PROCESSING / COMPLETED */
    private String status;
    private String errorMessage;
}
