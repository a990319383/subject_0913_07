package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ArticleCreateRequest {
    @NotBlank(message = "试验件编号不能为空")
    private String articleCode;

    @NotBlank(message = "试验件名称不能为空")
    private String articleName;

    private String targetModel;

    @NotBlank(message = "批次号不能为空")
    private String batchNo;

    private String remark;
}
