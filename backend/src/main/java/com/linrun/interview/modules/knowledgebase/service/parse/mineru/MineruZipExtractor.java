package com.linrun.interview.modules.knowledgebase.service.parse.mineru;

import com.linrun.interview.modules.knowledgebase.config.MineruProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 在内存中安全校验 MinerU ZIP，并且只提取 full.md。 */
@Component
public class MineruZipExtractor {

  private final MineruProperties properties;

  public MineruZipExtractor(MineruProperties properties) {
    this.properties = properties;
  }

  public String extractFullMarkdown(byte[] zipBytes) throws MineruClientException {
    if (zipBytes == null || zipBytes.length == 0) {
      throw new MineruClientException(
          MineruFailureCode.INVALID_RESPONSE, "MinerU 返回空 ZIP");
    }
    if (zipBytes.length > properties.getMaxZipBytes()) {
      throw new MineruClientException(
          MineruFailureCode.RESULT_TOO_LARGE, "MinerU ZIP 超过大小限制");
    }

    int entries = 0;
    long totalUncompressed = 0;
    byte[] markdown = null;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        entries++;
        if (entries > properties.getMaxZipEntries()) {
          throw security("ZIP entry 数量超过限制");
        }
        validateEntryName(entry.getName());
        if (entry.isDirectory()) {
          zip.closeEntry();
          continue;
        }

        boolean fullMarkdown = isFullMarkdown(entry.getName());
        ByteArrayOutputStream markdownOutput = fullMarkdown ? new ByteArrayOutputStream() : null;
        int read;
        while ((read = zip.read(buffer)) != -1) {
          totalUncompressed += read;
          validateTotalSize(totalUncompressed, zipBytes.length);
          if (fullMarkdown) {
            if (markdownOutput.size() + read > properties.getMaxMarkdownBytes()) {
              throw new MineruClientException(
                  MineruFailureCode.RESULT_TOO_LARGE, "full.md 超过大小限制");
            }
            markdownOutput.write(buffer, 0, read);
          }
        }
        if (fullMarkdown) {
          if (markdown != null) {
            throw new MineruClientException(
                MineruFailureCode.INVALID_RESPONSE, "MinerU ZIP 包含多个 full.md");
          }
          markdown = markdownOutput.toByteArray();
        }
        zip.closeEntry();
      }
    } catch (MineruClientException e) {
      throw e;
    } catch (Exception e) {
      throw new MineruClientException(
          MineruFailureCode.INVALID_RESPONSE, "MinerU ZIP 无法解析", e);
    }

    if (markdown == null || markdown.length == 0) {
      throw new MineruClientException(
          MineruFailureCode.FULL_MD_MISSING, "MinerU ZIP 缺少 full.md");
    }
    String result = new String(markdown, StandardCharsets.UTF_8).trim();
    if (result.isBlank()) {
      throw new MineruClientException(
          MineruFailureCode.FULL_MD_MISSING, "MinerU full.md 内容为空");
    }
    return result;
  }

  private void validateEntryName(String rawName) throws MineruClientException {
    if (rawName == null || rawName.isBlank() || rawName.indexOf('\0') >= 0
        || rawName.contains("\\")) {
      throw security("ZIP entry 路径非法");
    }
    try {
      Path raw = Path.of(rawName);
      Path normalized = raw.normalize();
      if (raw.isAbsolute() || normalized.isAbsolute()
          || normalized.startsWith("..") || rawName.matches("^[A-Za-z]:.*")) {
        throw security("ZIP entry 存在路径穿越");
      }
    } catch (MineruClientException e) {
      throw e;
    } catch (Exception e) {
      throw security("ZIP entry 路径非法");
    }
  }

  private void validateTotalSize(long totalUncompressed, int zipSize)
      throws MineruClientException {
    if (totalUncompressed > properties.getMaxUncompressedBytes()) {
      throw security("ZIP 解压总大小超过限制");
    }
    double ratio = totalUncompressed / (double) Math.max(zipSize, 1);
    if (ratio > properties.getMaxCompressionRatio()) {
      throw security("ZIP 压缩比超过限制");
    }
  }

  private boolean isFullMarkdown(String entryName) {
    String normalized = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
    return normalized.equals("full.md") || normalized.endsWith("/full.md");
  }

  private MineruClientException security(String message) {
    return new MineruClientException(MineruFailureCode.ZIP_SECURITY_VIOLATION, message);
  }
}
