package com.linrun.interview.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    
    // ========== 通用错误 1xxx ==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    
    // ========== 简历模块错误 2xxx ==========
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_UPLOAD_FAILED(2003, "简历上传失败"),
    RESUME_DUPLICATE(2004, "简历已存在"),
    RESUME_FILE_TYPE_NOT_SUPPORTED(2006, "不支持的文件类型"),
    RESUME_ANALYSIS_FAILED(2007, "简历分析失败"),
    RESUME_ANALYSIS_NOT_FOUND(2008, "简历分析结果不存在"),
    
    // ========== 面试模块错误 3xxx ==========
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_SESSION_EXPIRED(3002, "面试会话已过期"),
    INTERVIEW_QUESTION_NOT_FOUND(3003, "面试问题不存在"),
    INTERVIEW_ALREADY_COMPLETED(3004, "面试已完成"),
    INTERVIEW_EVALUATION_FAILED(3005, "面试评估失败"),
    INTERVIEW_QUESTION_GENERATION_FAILED(3006, "面试问题生成失败"),
    INTERVIEW_NOT_COMPLETED(3007, "面试尚未完成"),
    INTERVIEW_ANSWER_SAVE_FAILED(3008, "答案保存失败，请重试"),
    INTERVIEW_PREPARATION_NOT_READY(3009, "岗位实战尚未准备完成"),
    INTERVIEW_SESSION_VERSION_CONFLICT(3010, "面试会话版本冲突，请刷新后重试"),
    INTERVIEW_COMMAND_IN_PROGRESS(3011, "当前面试指令仍在处理中"),
    INTERVIEW_INVALID_STATE(3012, "当前面试状态不允许该操作"),
    INTERVIEW_RESUME_LIMIT_REACHED(3013, "该面试已超过续面次数或恢复期限"),
    INTERVIEW_REPORT_NOT_FOUND(3014, "面试复盘不存在"),
    INTERVIEW_REPORT_RETRY_NOT_ALLOWED(3015, "当前复盘状态不允许重试"),
    
    // ========== 存储模块错误 4xxx ==========
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),
    
    // ========== 导出模块错误 5xxx ==========
    EXPORT_PDF_FAILED(5001, "PDF导出失败"),
    
    // ========== 知识库模块错误 6xxx ==========
    KNOWLEDGE_BASE_NOT_FOUND(6001, "知识库不存在"),
    KNOWLEDGE_BASE_PARSE_FAILED(6002, "知识库文件解析失败"),
    KNOWLEDGE_BASE_QUERY_FAILED(6004, "知识库查询失败"),
    KNOWLEDGE_BASE_DELETE_FAILED(6005, "知识库删除失败"),
    KNOWLEDGE_BASE_VECTORIZATION_FAILED(6006, "知识库向量化失败"),
    
    // ========== AI服务错误 7xxx ==========
    AI_SERVICE_UNAVAILABLE(7001, "AI服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(7002, "AI服务响应超时"),
    AI_SERVICE_ERROR(7003, "AI服务调用失败"),
    AI_API_KEY_INVALID(7004, "AI服务密钥无效"),
    AI_RATE_LIMIT_EXCEEDED(7005, "AI服务调用频率超限"),
    USER_LLM_NOT_CONFIGURED(7006, "尚未配置你的模型访问凭证，请先在设置中配置"),

    // ========== 限流模块错误 8xxx ==========
    RATE_LIMIT_EXCEEDED(8001, "请求过于频繁，请稍后再试"),

    // ========== 面试日程模块错误 9xxx ==========
    INTERVIEW_SCHEDULE_NOT_FOUND(9001, "面试日程不存在"),

    // ========== Provider管理模块错误 11xxx ==========
    PROVIDER_NOT_FOUND(11001, "LLM Provider 不存在"),
    PROVIDER_ALREADY_EXISTS(11002, "LLM Provider 已存在"),
    PROVIDER_CONFIG_READ_FAILED(11004, "读取 Provider 配置失败"),
    PROVIDER_CONFIG_WRITE_FAILED(11005, "写入 Provider 配置失败"),
    PROVIDER_TEST_FAILED(11006, "Provider 连通性测试失败"),
    PROVIDER_DEFAULT_CANNOT_DELETE(11007, "默认 Provider 不可删除"),
    MODULE_NOT_FOUND(11008, "模块不存在"),

    // ========== GitHub 代码证据模块错误 12xxx ==========
    GITHUB_INVALID_REPOSITORY_URL(12001, "GitHub 仓库 URL 非法"),
    GITHUB_REPOSITORY_NOT_FOUND(12002, "GitHub 仓库不存在"),
    GITHUB_API_UNAVAILABLE(12003, "GitHub API 暂时不可用"),
    GITHUB_RATE_LIMITED(12004, "GitHub API 调用频率受限"),
    GITHUB_SYNC_LIMIT_EXCEEDED(12005, "GitHub 同步范围超过安全上限"),
    GITHUB_REPOSITORY_NOT_READY(12006, "GitHub 仓库证据尚未就绪"),
    GITHUB_EVIDENCE_NOT_FOUND(12007, "GitHub 代码证据不存在"),

    // ========== 算法面试模块错误 13xxx ==========
    CODING_PROBLEM_NOT_FOUND(13001, "算法题不存在"),
    CODING_PROBLEM_NOT_ENABLED(13002, "算法题或所选语言尚未启用"),
    CODING_ATTEMPT_NOT_FOUND(13003, "算法作答不存在"),
    CODING_DRAFT_CONFLICT(13004, "草稿版本冲突，请先恢复最新草稿"),
    JUDGE_SUBMISSION_NOT_FOUND(13005, "判题记录不存在"),
    JUDGE_REJUDGE_NOT_ALLOWED(13006, "当前判题状态不允许补判");

    private final Integer code;
    private final String message;
}
