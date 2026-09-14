package com.evops.vo;

import lombok.Data;

/**
 * 导入错误明细（逐字段）：原始行号、字段、原值、失败原因。
 */
@Data
public class ImportErrorVo {
    private Integer shardNo;
    private Integer lineNo;
    private String recordType;
    private String bizKey;
    private String fieldName;
    private String rawValue;
    private String reason;
}
