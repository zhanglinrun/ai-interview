package com.linrun.interview.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class AttachmentResponseBuilder {

  private AttachmentResponseBuilder() {
  }

  public static ResponseEntity<byte[]> pdf(String filename, byte[] bytes) {
    return attachment(filename, MediaType.APPLICATION_PDF_VALUE, bytes);
  }

  public static ResponseEntity<byte[]> attachment(
      String filename,
      String contentType,
      byte[] bytes) {
    String encodedFilename = encodeFilename(filename);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(encodedFilename))
        .header(HttpHeaders.CONTENT_TYPE, resolveContentType(contentType))
        .body(bytes);
  }

  private static String contentDisposition(String encodedFilename) {
    return "attachment; filename=\"" + encodedFilename
        + "\"; filename*=UTF-8''" + encodedFilename;
  }

  private static String resolveContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
    return contentType;
  }

  private static String encodeFilename(String filename) {
    return URLEncoder.encode(filename, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }
}
