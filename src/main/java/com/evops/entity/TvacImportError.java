package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入错误明细：保留原始行号、字段、原值与失败原因。
 */
@Data
@TableName("t_tvac_import_error")
public class TvacImportError {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Integer shardNo;
    /** CSV 原始行号 */
    private Integer lineNo;
    private String recordType;
    private String bizKey;
    /** 出错字段（缺列等结构性错误用 __ROW__/__HEADER__） */
    private String fieldName;
    /** 原始取值 */
    private String rawValue;
    /** 失败原因 */
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
