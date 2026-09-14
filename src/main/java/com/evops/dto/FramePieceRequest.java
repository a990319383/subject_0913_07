package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 地面站发送的一个通道分片（二进制解码结果）。允许乱序到达、重复重发。
 */
@Data
public class FramePieceRequest {
    @NotBlank(message = "会话键不能为空")
    private String sessionKey;
    @NotBlank(message = "通道编号不能为空")
    private String channelCode;
    /** 片序号（同一通道在逻辑帧内的顺序），从 1 起 */
    @NotNull(message = "片序号不能为空")
    @Positive(message = "片序号从1开始")
    private Integer pieceSeq;
    /** 二进制解码后的报文体（分片校验和对其复算 CRC32） */
    @NotBlank(message = "分片报文不能为空")
    private String payload;
    /** 地面站客户端给出的分片 CRC32（十六进制）；为空则以服务端复算值为准 */
    private String clientChecksum;
    private Integer cycleNo;
    private LocalDateTime frameTime;
    /** 解码出的原始值（可选留痕） */
    private String rawValue;
    /** 解码出的工程值（用于落遥测帧与越限判读） */
    private BigDecimal engValue;
}
