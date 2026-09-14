package com.evops.dto;

import lombok.Data;

@Data
public class ArticleUpdateRequest {
    private String articleName;
    private String targetModel;
    private String remark;
}
