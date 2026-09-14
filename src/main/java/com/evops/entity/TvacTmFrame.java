package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 遥测帧：某通道在某循环某时刻的一帧工程值
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_tm_frame")
public class TvacTmFrame extends BaseEntity {
    /** 帧序号（业务唯一键） */
    private String frameSeq;
    private Long planId;
    private Long channelId;
    private Long articleId;
    /** 循环次序号 */
    private Integer cycleNo;
    /** 收帧时刻 */
    private LocalDateTime frameTime;
    /** 原始值（报文字符串） */
    private String rawValue;
    /** 工程值 */
    private BigDecimal engValue;
    /** 越限标记：NORMAL/HIGH/LOW */
    private String limitFlag;
    /** 来源地面站设备编号 */
    private String sourceDevice;
}
