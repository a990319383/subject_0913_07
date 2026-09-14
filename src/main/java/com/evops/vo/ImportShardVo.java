package com.evops.vo;

import lombok.Data;

/**
 * 分片处理结果。
 */
@Data
public class ImportShardVo {
    private Long id;
    private Integer shardNo;
    private Integer lineStart;
    private Integer lineEnd;
    private Integer rowCount;
    private Integer successCount;
    private Integer updatedCount;
    private Integer failedCount;
    private String shardChecksum;
    /** PENDING/PROCESSING/SUCCESS/FAILED */
    private String status;
    private Integer attempts;
    private String errorMessage;
}
