package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 二进制解码帧重组会话：一个带帧号的逻辑帧由多个通道分片构成。
 * 分片允许乱序到达、重复重发，收齐后触发逐通道重组。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_frame_session")
public class TvacFrameSession extends BaseEntity {
    private String sessionKey;
    private String frameNo;
    private Long articleId;
    private Long planId;
    private Integer expectedPieces;
    private Integer goodPieces;
    private Integer badPieces;
    /** 来源地面站设备编号 */
    private String sourceDevice;
    /** ASSEMBLING / ASSEMBLED */
    private String status;
    private LocalDateTime assembledTime;
    private String remark;
}
