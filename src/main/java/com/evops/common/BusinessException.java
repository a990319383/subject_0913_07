package com.evops.common;

/**
 * 业务规则异常：由全局异常处理器转换为 success=false 的统一返回。
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }

    public static BusinessException of(String message) {
        return new BusinessException(message);
    }
}
