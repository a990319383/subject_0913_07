package com.evops.vo;

import lombok.Data;

/**
 * 运营检索单条结果（主记录一行，一对多关联不放大主表）。
 * 关联统计均为标量子查询/聚合后的单值字段。
 */
@Data
public class OperationSearchItemVo {
    /** 主体类型 ARTICLE / PLAN */
    private String objectType;

    // ---------- 主记录 ----------
    private Long id;
    private String code;
    private String name;
    private String status;
    private Long tenantId;
    private java.time.LocalDateTime createTime;

    // ---------- 试验件主体附带 / 计划主体所属 ----------
    private Long articleId;
    private String articleCode;
    private String articleName;
    private String targetModel;
    private String batchNo;
    private String articleStatus;

    // ---------- 计划主体附带 ----------
    private Long planId;
    private String planCode;
    private String planName;
    private String planStatus;
    private java.time.LocalDateTime planStartTime;
    private java.time.LocalDateTime planEndTime;
    private Integer targetCycles;

    // ---------- 标量聚合（按主记录ID单独聚合，不经一对多 JOIN 产生） ----------
    private Long planCount;
    private Long curvePointCount;
    private Long frameCount;
    private Long abnormalFrameCount;
}
