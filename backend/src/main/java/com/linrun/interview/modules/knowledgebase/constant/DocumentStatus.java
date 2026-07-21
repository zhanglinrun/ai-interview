package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 知识库文档状态机（对齐业界实践 DocumentStatus）。
 *
 * <p>流转：{@link #INIT} → {@link #UPLOADED} → {@link #CONVERTING} → {@link #CONVERTED}
 * → {@link #CHUNKED} → {@link #VECTOR_STORED}。无需向量存储的走 {@link #STORED}。
 *
 * <p>业务状态不额外引入 FAILED。向量化失败时文档保持 {@link #CHUNKED}，任务租约、尝试次数、
 * 下次重试时间、最后错误和终止标记单独持久化在版本记录中；事件触发失败后由定时补偿执行
 * 有界重试，达到最大次数后停止自动重试，等待人工修复配置并重置任务。
 */
public enum DocumentStatus {
    /** 初始状态。 */
    INIT,
    /** 上传完成。 */
    UPLOADED,
    /** 转换中（解析为 Markdown）。 */
    CONVERTING,
    /** 转换完成。 */
    CONVERTED,
    /** 分块完成（segment 已落库，待向量化）。 */
    CHUNKED,
    /** 向量存储完成。 */
    VECTOR_STORED,
    /** 存储完成（不需要向量存储）。 */
    STORED
}
