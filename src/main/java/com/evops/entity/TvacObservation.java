package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归一化观测数据台账：record_type + biz_key 唯一。
 * 这是文件校验和之外的第二道幂等防线，也是 UPDATED 覆盖更新的锚点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_observation")
public class TvacObservation extends BaseEntity {
    private Long batchId;
    /** CURVE / FRAME / REPORT */
    private String recordType;
    /** 业务键（曲线: plan|cycle|offset；帧: frame_seq；结论: report_no） */
    private String bizKey;
    private Long articleId;
    private Long planId;
    private Long channelId;
    private String targetTable;
    private Long targetId;
    private String objectCode;
    private String planCode;
    private String channelCode;
    private LocalDateTime observeTime;
    private Integer cycleNo;
    private Integer offsetSec;
    private BigDecimal temperatureC;
    private BigDecimal pressurePa;
    private String frameSeq;
    private String rawValue;
    private BigDecimal engValue;
    private String limitFlag;
    private String conclusion;
    private String sourceDevice;
    /** IMPORTED / UPDATED / REJECTED */
    private String status;
}
