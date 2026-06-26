package com.linrun.interview.modules.knowledgebase.service.parse;

import com.linrun.interview.modules.knowledgebase.constant.FileType;

/**
 * 文件解析服务接口（对齐 know-engine FileProcessService）。
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
}
