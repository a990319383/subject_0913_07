package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 逐行导入结果留痕：成功/更新/失败全部记录。
 */
@Data
@TableName("t_tvac_import_row")
public class TvacImportRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    private Integer shardNo;
    /** CSV 原始行号（含表头，从 1 起） */
    private Integer lineNo;
    /** CURVE/FRAME/REPORT */
    private String recordType;
    private String bizKey;
    private String objectCode;
    /** SUCCESS/UPDATED/FAILED */
    private String result;
    private String targetTable;
    private Long targetId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
