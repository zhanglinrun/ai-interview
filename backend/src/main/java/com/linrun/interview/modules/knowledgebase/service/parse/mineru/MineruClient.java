package com.linrun.interview.modules.knowledgebase.service.parse.mineru;

import java.net.URI;

/** 可替换的 MinerU 官方 API Client。 */
public interface MineruClient {

  String submit(URI sourceUrl, String modelVersion) throws MineruClientException;

  MineruTaskResult getTask(String providerTaskId) throws MineruClientException;

  byte[] downloadResult(URI resultZipUrl) throws MineruClientException;
}
