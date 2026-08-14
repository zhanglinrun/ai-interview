package com.linrun.interview.document.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 MinerU Markdown 中的相对图片路径替换为 MinIO URL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MineruMarkdownImageRewriter {

 private static final Pattern IMAGE_TAG = Pattern.compile("!\\[(.*?)]\\(([^)]+)\\)");

 private final ImageDescriptionService imageDescriptionService;

 public String rewrite(String markdown, Map<String, String> imageUrlByFileName) {
 if (markdown == null || markdown.isBlank()
 || imageUrlByFileName == null || imageUrlByFileName.isEmpty()) {
 return markdown;
 }
 Matcher matcher = IMAGE_TAG.matcher(markdown);
 StringBuilder result = new StringBuilder();
 while (matcher.find()) {
 String altText = matcher.group(1);
 String imagePath = matcher.group(2);
 String imageName = Paths.get(imagePath.replace('\\', '/')).getFileName().toString();
 String minioUrl = imageUrlByFileName.get(imageName);
 if (minioUrl == null) {
 log.debug("未找到图片映射，保留原引用: {}", imagePath);
 matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
 continue;
 }
 String description = imageDescriptionService.describe(minioUrl);
 String newAlt = description != null && !description.isBlank() ? description : altText;
 String replacement = "![" + newAlt + "](" + minioUrl + ")";
 matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
 }
 matcher.appendTail(result);
 return result.toString();
 }
}
