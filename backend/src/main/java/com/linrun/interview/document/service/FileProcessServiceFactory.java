package com.linrun.interview.document.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件解析器工厂（对齐业界实践 FileProcessServiceFactory）。
 *
 * <p>Spring 注入所有 {@link FileProcessService} 实现，按 {@link FileType} 选第一个
 * {@link FileProcessService#supports(FileType)} 为 true 的实现。无匹配时抛业务异常。
 *
 * <p>DOCUMENT_SEARCH 的 Excel/CSV 不在工厂路由：{@code DocumentProcessServiceImpl}
 * 在 convert 阶段直接将 {@code convertedDocUrl} 设为原文件 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessServiceFactory {

    private final List<FileProcessService> processors;

    /**
     * 按文件类型选择解析器。
     *
     * @throws BusinessException 无匹配解析器时
     */
    public FileProcessService get(FileType fileType) {
        return get(fileType, KnowledgeBaseType.DOCUMENT_SEARCH);
    }

    /**
     * 按文件类型与知识库类型选择解析器。
     */
    public FileProcessService get(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        KnowledgeBaseType kbType = knowledgeBaseType != null
            ? knowledgeBaseType : KnowledgeBaseType.DOCUMENT_SEARCH;
        for (FileProcessService processor : processors) {
            if (processor.supports(fileType, kbType)) {
                log.debug("选择文件解析器: fileType={}, kbType={}, processor={}",
                    fileType, kbType, processor.getClass().getSimpleName());
                return processor;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "不支持的文件类型: " + fileType + "（知识库类型: " + kbType + "），无可用解析器");
    }
}
