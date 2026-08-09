package com.linrun.interview.document.service;

import com.linrun.interview.document.vo.DocumentParseRequest;
import com.linrun.interview.document.constant.FileType;

/**
 * 文件解析服务接口（对齐业界实践 FileProcessService）。
 *
 * <p>不同 {@link FileType} 由不同实现处理（PDF/DOC 走 MinerU，Markdown 直读），
 * 由 {@link FileProcessServiceFactory} 按 {@link #supports(FileType)} 选择。
 * 解析产出 Markdown 文本（保留标题层级、表格、公式），供切块器按标题层级切分。
 */
public interface FileProcessService {

    /**
     * 当前实现是否支持该文件类型。
     */
    boolean supports(FileType fileType);

    /**
     * 解析文件字节数据为 Markdown 文本。
     *
     * @param fileBytes  文件字节
     * @param fileName   原始文件名（用于类型推断/日志）
     * @return Markdown 文本
     */
    String processDocument(byte[] fileBytes, String fileName);

    /**
     * 带持久化和对象存储上下文的解析入口。旧解析器默认只消费字节；MinerU 使用 storageKey
     * 生成短时预签名 URL，并把 provider task 状态关联到文档版本。
     */
    default String processDocument(DocumentParseRequest request) {
        return processDocument(request.fileBytes(), request.fileName());
    }
}
