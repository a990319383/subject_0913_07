package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 试验件（受试航天器产品/设备）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_article")
public class TvacArticle extends BaseEntity {
    /** 试验件编号（业务唯一键） */
    private String articleCode;
    /** 试验件名称 */
    private String articleName;
    /** 目标型号 */
    private String targetModel;
    /** 批次号 */
    private String batchNo;
    /** 所属租户（为空表示平台/历史遗留数据，仅系统角色可见） */
    private Long tenantId;
    /** 状态：REGISTERED/IN_TEST/COMPLETED/SCRAPPED */
    private String status;
    private String remark;
}
