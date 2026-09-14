package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对象级数据授权（TENANT_VIEWER -> 试验件）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tvac_user_grant")
public class TvacUserGrant extends BaseEntity {
    private Long userId;
    /** ARTICLE / PLAN */
    private String objectType;
    private Long articleId;
}
