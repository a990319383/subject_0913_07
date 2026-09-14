package com.evops.vo;

import lombok.Data;

/**
 * 试验件批次建档视图：试验件 + 批次内件数
 */
@Data
public class ArticleBatchVo {
    private String batchNo;
    private String targetModel;
    private Long articleCount;
}
