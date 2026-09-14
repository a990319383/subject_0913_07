package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批次建档：同一批次号下一次登记多件试验件。
 */
@Data
public class ArticleBatchCreateRequest {

    @NotBlank(message = "批次号不能为空")
    private String batchNo;

    private String targetModel;

    @NotEmpty(message = "批次内试验件不能为空")
    @Valid
    private List<Item> articles;

    @Data
    public static class Item {
        @NotBlank(message = "试验件编号不能为空")
        private String articleCode;

        @NotBlank(message = "试验件名称不能为空")
        private String articleName;

        private String remark;
    }
}
