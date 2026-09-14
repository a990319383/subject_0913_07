package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 帧分片：某通道在某片序号上的二进制解码结果。
 * 到片即复算 CRC32 分片校验和；坏片单通道隔离，重复片幂等丢弃。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_frame_piece")
public class TvacFramePiece extends BaseEntity {
    private Long sessionId;
    private Long channelId;
    /** 片序号（同一通道在帧内的顺序） */
    private Integer pieceSeq;
    /** 地面站实际发送顺序（乱序到达时与 pieceSeq 不一致） */
    private Integer receiveSeq;
    private String payload;
    private String rawValue;
    private BigDecimal engValue;
    private Integer cycleNo;
    private LocalDateTime frameTime;
    private String clientChecksum;
    private String serverChecksum;
    /** VERIFIED / BAD / DUPLICATED */
    private String status;
    private String badReason;
}
