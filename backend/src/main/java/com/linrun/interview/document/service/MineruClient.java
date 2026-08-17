package com.linrun.interview.document.service;

import com.linrun.interview.document.vo.MineruTaskResult;
import java.net.URI;

/** 可替换的 MinerU 官方 API Client。 */
public interface MineruClient {

  String submit(URI sourceUrl, String modelVersion) throws MineruClientException;

  /**
   * 官方本地文件路径：申请上传链 → PUT 到 MinerU OSS → 返回 batch_id。
   * {@code pageRanges} 例如 {@code 1-200}，超过 200 页必须切片。
   */
  String submitLocalFile(
      byte[] content,
      String fileName,
      String modelVersion,
      String pageRanges
  ) throws MineruClientException;

  MineruTaskResult getTask(String providerTaskId) throws MineruClientException;

  MineruTaskResult getBatchResult(String batchId) throws MineruClientException;

  byte[] downloadResult(URI resultZipUrl) throws MineruClientException;
}
