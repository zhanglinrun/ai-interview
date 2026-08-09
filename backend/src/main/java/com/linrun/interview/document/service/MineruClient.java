package com.linrun.interview.document.service;

import com.linrun.interview.document.vo.MineruTaskResult;
import java.net.URI;

/** 可替换的 MinerU 官方 API Client。 */
public interface MineruClient {

  String submit(URI sourceUrl, String modelVersion) throws MineruClientException;

  MineruTaskResult getTask(String providerTaskId) throws MineruClientException;

  byte[] downloadResult(URI resultZipUrl) throws MineruClientException;
}
