package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;

/**
 * 开始一次二进制解码帧重组会话。一个带帧号的逻辑帧横跨多个通道分片。
 */
@Data
public class FrameSessionStartRequest {
    /** 会话键，留空则按 计划编号#帧号 确定性生成，保证地面站重发幂等 */
    private String sessionKey;
    @NotBlank(message = "帧号不能为空")
    private String frameNo;
    @NotBlank(message = "计划编号不能为空")
    private String planCode;
    /** 预期分片数；为空表示不自动触发，等地面站显式调用 assemble */
    @Positive(message = "预期分片数必须为正数")
    private Integer expectedPieces;
    /** 来源地面站设备编号 */
    private String sourceDevice;
}
