package com.linrun.interview.document.vo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * MinerU 结果 ZIP 解包产物：Markdown 正文 + 同包图片字节。
 */
public record MineruExtractedPackage(
 String markdown,
 Map<String, byte[]> images
) {

 public MineruExtractedPackage {
 markdown = Objects.requireNonNull(markdown, "markdown");
 images = images == null || images.isEmpty()
 ? Map.of()
 : Collections.unmodifiableMap(new LinkedHashMap<>(images));
 }
}
