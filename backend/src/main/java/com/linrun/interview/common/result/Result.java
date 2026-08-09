package com.linrun.interview.common.result;

import com.linrun.interview.common.constant.CommonConstants;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.observability.TraceContext;
import lombok.Getter;

/**
 * 统一响应结果
 */
@Getter
public class Result<T> {
    
    private final Integer code;
    private final String message;
    private final T data;
    private final String traceId;
    
    private Result(Integer code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    private static String currentTraceId() {
        return TraceContext.getTraceId();
    }
    
    // ========== 成功响应 ==========
    
    public static <T> Result<T> success() {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", null, currentTraceId());
    }
    
    public static <T> Result<T> success(T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", data, currentTraceId());
    }
    
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, message, data, currentTraceId());
    }
    
    // ========== 失败响应 ==========
    
    public static <T> Result<T> error(String message) {
        return new Result<>(CommonConstants.StatusCode.SERVER_ERROR, message, null, currentTraceId());
    }
    
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null, currentTraceId());
    }
    
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, currentTraceId());
    }
    
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, currentTraceId());
    }
    
    // ========== 辅助方法 ==========
    
    public boolean isSuccess() {
        return CommonConstants.StatusCode.SUCCESS == this.code;
    }
}
