package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 导入分片：独立事务、独立 CRC32 校验和，FAILED 可用原报文重试。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_import_shard")
public class TvacImportShard extends BaseEntity {
    private Long batchId;
    private Integer shardNo;
    private Integer lineStart;
    private Integer lineEnd;
    private Integer rowCount;
    private Integer successCount;
    private Integer updatedCount;
    private Integer failedCount;
    /** 分片原文 CRC32（hex），重试复算比对 */
    private String shardChecksum;
    /** 分片原始 CSV 报文 */
    private String shardPayload;
    /** PENDING/PROCESSING/SUCCESS/FAILED */
    private String status;
    private Integer attempts;
    private String errorMessage;
}
