package com.linrun.interview.document.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 通用文档解析接口（Tika 实现见 {@code impl.DocumentParseServiceImpl}）。
 */
public interface DocumentParseService {

 String parseContent(MultipartFile file);

 String parseContent(byte[] fileBytes, String fileName);

 String downloadAndParseContent(FileStorageService storageService, String storageKey, String originalFilename);
}
