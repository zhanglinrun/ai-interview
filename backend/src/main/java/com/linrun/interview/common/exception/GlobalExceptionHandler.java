package com.linrun.interview.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.linrun.interview.common.result.Result;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(
            BusinessException e,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (response.isCommitted()
                || (request.getDispatcherType() == DispatcherType.ASYNC
                    && isEventStream(response.getContentType()))) {
            log.debug(
                "异步流式响应已结束，跳过业务异常的二次写入: code={}, committed={}, contentType={}",
                e.getCode(), response.isCommitted(), response.getContentType());
            return null;
        }
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * Sa-Token 未登录不能落到「系统繁忙」，否则前端无法按 401 静默刷新 token。
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        log.warn("未登录: {}", e.getMessage());
        return Result.error(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
    }
    
    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST, message);
    }
    
    /**
     * 处理绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 处理缺失、空值或类型不匹配的查询参数。
     */
    @ExceptionHandler({
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRequestParameterException(Exception e) {
        log.warn("请求参数格式错误: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, "请求参数不完整或格式不正确");
    }

    /**
     * 处理无法读取或 Content-Type 不支持的请求体。
     */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        HttpMediaTypeNotSupportedException.class
    })
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRequestBodyException(Exception e) {
        log.warn("请求内容格式错误: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, "请求内容格式不正确");
    }
    
    /**
     * 处理文件上传大小超限异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传大小超限: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, "文件大小超过限制");
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMultipartException(MultipartException e) {
        if (isPartCountExceeded(e)) {
            log.warn("multipart 文件数超限: {}", e.getMessage());
            return Result.error(ErrorCode.BAD_REQUEST, "单次上传文件过多，请减少数量后重试");
        }
        log.warn("multipart 解析失败: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, "上传请求无法解析，请检查文件后重试");
    }

    static boolean isPartCountExceeded(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            String typeName = current.getClass().getName();
            if (typeName.contains("FileCountLimitExceededException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("max part")
                        || normalized.contains("part count")
                        || normalized.contains("number of parts")) {
                    return true;
                }
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }
    
    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, e.getMessage());
    }
    
    /**
     * 处理 AI 服务网络异常（SSL握手失败、连接超时等）
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */
    @ExceptionHandler(ResourceAccessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleResourceAccessException(ResourceAccessException e) {
        log.error("AI服务连接失败: {}", e.getMessage(), e);
        
        // 判断具体异常类型
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return Result.error(ErrorCode.AI_SERVICE_TIMEOUT, "AI服务响应超时，请稍后重试");
        }
        
        // SSL握手失败或其他网络问题
        String message = e.getMessage();
        if (message != null && message.contains("handshake")) {
            return Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务连接失败（网络不稳定），请检查网络或稍后重试");
        }
        
        return Result.error(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI服务暂时不可用，请稍后重试");
    }
    
    /**
     * 处理 AI 服务调用异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */
    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleRestClientException(RestClientException e) {
        log.error("AI服务调用失败: {}", e.getMessage(), e);
        
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("401") || message.contains("Unauthorized")) {
                return Result.error(ErrorCode.AI_API_KEY_INVALID, "AI服务密钥无效，请联系管理员");
            }
            if (message.contains("429") || message.contains("Too Many Requests")) {
                return Result.error(ErrorCode.AI_RATE_LIMIT_EXCEEDED, "AI服务调用过于频繁，请稍后重试");
            }
        }
        
        return Result.error(ErrorCode.AI_SERVICE_ERROR, "AI服务调用失败，请稍后重试");
    }
    
    /**
     * 处理 404 - 资源未找到异常
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("资源未找到: {}", e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND, "API 接口不存在");
    }

    /**
     * 处理请求方法不支持异常
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {} {}", e.getMethod(), e.getSupportedHttpMethods());
        return Result.error(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
    }

    /**
     * SSE/流式响应已被浏览器刷新、路由切换等动作关闭时，响应对象已经不可再写。
     *
     * <p>Spring 官方的 {@code ResponseEntityExceptionHandler} 对该异常同样返回 {@code null}；
     * 这里显式吞掉响应写入，避免继续把统一 JSON 写入 {@code text/event-stream}，进而产生
     * {@link HttpMessageNotWritableException}。客户端断连属于正常生命周期事件，不打印堆栈。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        if (isClientDisconnect(e)) {
            log.debug("客户端已断开异步响应连接，跳过错误响应写回: causeType={}",
                disconnectCauseType(e));
            return;
        }
        // 即使并非典型 ClientAbort，AsyncRequestNotUsableException 也表示响应已不可用，不能二次写。
        log.warn("异步响应已不可用，跳过二次写入", e);
    }

    /**
     * 容器有时直接抛出 ClientAbortException/IOException，而不是包装成
     * AsyncRequestNotUsableException。只忽略明确的连接中止；其他 I/O 异常仍走统一错误响应。
     */
    @ExceptionHandler(IOException.class)
    public Result<Void> handleIOException(IOException e) {
        if (isClientDisconnect(e)) {
            log.debug("客户端已断开响应连接，跳过错误响应写回: causeType={}",
                disconnectCauseType(e));
            return null;
        }
        return handleException(e);
    }

    /** 已提交的 SSE 响应无法改写为 JSON；未提交的真实序列化异常仍走原统一响应。 */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public Result<Void> handleHttpMessageNotWritableException(
            HttpMessageNotWritableException e,
            HttpServletResponse response) {
        if (response.isCommitted() || isEventStream(response.getContentType())) {
            log.warn("流式响应已提交，跳过不可写异常的二次响应: committed={}, contentType={}",
                response.isCommitted(), response.getContentType());
            return null;
        }
        return handleException(e);
    }

    /**
     * 处理其他未知异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        // 异步框架/容器可能再包一层 RuntimeException；最终原因仍是断连时不能回写 JSON。
        if (isClientDisconnect(e)) {
            log.debug("客户端已断开响应连接，跳过错误响应写回: causeType={}",
                disconnectCauseType(e));
            return null;
        }
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
    }

    static boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            String typeName = current.getClass().getName();
            if (typeName.endsWith("ClientAbortException")
                    || typeName.endsWith("EofException")
                    || current instanceof ClosedChannelException) {
                return true;
            }
            if (current instanceof IOException && isDisconnectMessage(current.getMessage())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static boolean isDisconnectMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("broken pipe")
                || normalized.contains("connection reset")
                || normalized.contains("connection abort")
                || normalized.contains("software caused connection abort")
                || normalized.contains("远程主机强迫关闭")
                || normalized.contains("软件中止了一个已建立的连接");
    }

    private static String disconnectCauseType(Throwable error) {
        Throwable current = error;
        String type = error == null ? "unknown" : error.getClass().getSimpleName();
        for (int depth = 0; current != null && depth < 16; depth++) {
            type = current.getClass().getSimpleName();
            Throwable next = current.getCause();
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        return type;
    }

    private static boolean isEventStream(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT)
                    .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
