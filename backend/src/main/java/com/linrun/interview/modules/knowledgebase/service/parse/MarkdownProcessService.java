package com.linrun.interview.modules.knowledgebase.service.parse;

import com.linrun.interview.modules.knowledgebase.constant.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Markdown/TXT 文件解析器（对齐 know-engine MarkdownProcessServiceImpl）。
 *
 * <p>Markdown 与纯文本无需结构化转换，直接按 UTF-8 读取为文本。
 * 图片描述增强（视觉模型）暂未启用，预留扩展点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkdownProcessService implements FileProcessService {

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.MARKDOWN || fileType == FileType.TXT;
    }

    @Override
    public String processDocument(byte[] fileBytes, String fileName) {
        log.info("解析 Markdown/TXT 文件: fileName={}, bytes={}", fileName, fileBytes.length);
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        // BOM 去除
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text;
    }
}
