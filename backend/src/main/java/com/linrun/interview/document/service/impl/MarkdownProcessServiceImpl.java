package com.linrun.interview.document.service.impl;

import com.linrun.interview.document.service.FileProcessService;
import com.linrun.interview.document.constant.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown/TXT 文件解析器：读取 UTF-8 文本，并为公网图片 URL 生成 alt 描述。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkdownProcessServiceImpl implements FileProcessService {

    private static final Pattern IMAGE_TAG = Pattern.compile("!\\[(.*?)]\\(([^)]+)\\)");

    private final ImageDescriptionService imageDescriptionService;

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.MARKDOWN || fileType == FileType.TXT;
    }

    @Override
    public String processDocument(byte[] fileBytes, String fileName) {
        log.info("解析 Markdown/TXT 文件: fileName={}, bytes={}", fileName, fileBytes.length);
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return enrichPublicImageAlts(text);
    }

    private String enrichPublicImageAlts(String markdown) {
        Matcher matcher = IMAGE_TAG.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String altText = matcher.group(1);
            String imageUrl = matcher.group(2);
            if (!isPublicHttpUrl(imageUrl)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String description = imageDescriptionService.describe(imageUrl);
            String newAlt = description != null && !description.isBlank() ? description : altText;
            String replacement = "![" + newAlt + "](" + imageUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean isPublicHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }
}
