package com.evops.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营检索请求：试验件 / 试验计划双主体组合筛选。
 * 所有条件之间为 AND；成对的 From/To 构成闭区间范围条件。
 * 至少 4 个条件可同时组合（编号/名称/型号/批次/状态/日期区间/温压曲线区间/循环次）。
 */
@Data
public class OperationSearchRequest {

    /** 检索主体：ARTICLE=试验件（默认），PLAN=试验计划 */
    private String objectType = "ARTICLE";

    // ---------- 试验件维度条件 ----------
    private Long articleId;
    /** 精确匹配 */
    private String articleCode;
    /** 模糊匹配 */
    private String articleName;
    private String targetModel;
    private String batchNo;
    private String articleStatus;

    // ---------- 试验计划维度条件（试验件检索时以 EXISTS 半连接下推，不放大主记录） ----------
    private Long planId;
    private String planCode;
    private String planName;
    private String planStatus;

    // ---------- 日期范围（闭区间） ----------
    /** 主体建档时间范围 */
    private LocalDateTime createTimeFrom;
    private LocalDateTime createTimeTo;
    /** 计划开始时间范围（试验件检索时为“存在计划落入该区间”） */
    private LocalDateTime planTimeFrom;
    private LocalDateTime planTimeTo;

    // ---------- 温压曲线组合（存在同一采样点同时落入温/压区间） ----------
    private BigDecimal tempFromC;
    private BigDecimal tempToC;
    private BigDecimal pressureFromPa;
    private BigDecimal pressureToPa;
    /** 曲线点所属循环次 */
    private Integer curveCycleNo;

    // ---------- 分页（pageSize 强制 1-100） ----------
    @Min(value = 1, message = "页码从1开始")
    private Integer pageNum = 1;

    @Min(value = 1, message = "pageSize 只能为 1-100")
    @Max(value = 100, message = "pageSize 只能为 1-100")
    private Integer pageSize = 20;

    /** 游标分页令牌（与上一页响应的 nextCursor 相同；存在时优先于 pageNum） */
    private String cursor;

    // ---------- 游标解码后的内部字段（不接收外部 JSON） ----------
    @JsonIgnore
    private LocalDateTime cursorTime;
    @JsonIgnore
    private Long cursorId;

    /** 页码偏移量（仅页码分页使用，游标分页忽略） */
    @JsonIgnore
    public long getOffset() {
        int page = pageNum == null ? 1 : pageNum;
        int size = pageSize == null ? 20 : pageSize;
        return (long) (page - 1) * size;
    }
}
