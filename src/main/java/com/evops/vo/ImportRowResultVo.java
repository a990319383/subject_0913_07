package com.evops.vo;

import lombok.Data;

/**
 * 逐行导入结果：SUCCESS（新增）/ UPDATED（命中业务键覆盖）/ FAILED（隔离）。
 */
@Data
public class ImportRowResultVo {
    private Integer shardNo;
    private Integer lineNo;
    private String recordType;
    private String bizKey;
    private String objectCode;
    private String result;
    private String targetTable;
    private Long targetId;
}
