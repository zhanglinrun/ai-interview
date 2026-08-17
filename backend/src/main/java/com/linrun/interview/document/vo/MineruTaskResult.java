package com.linrun.interview.document.vo;

import com.linrun.interview.document.constant.MineruTaskStatus;
import java.net.URI;

public record MineruTaskResult(
    MineruTaskStatus status,
    URI resultZipUrl,
    String failureMessage,
    Integer totalPages
) {

  public MineruTaskResult(MineruTaskStatus status, URI resultZipUrl, String failureMessage) {
    this(status, resultZipUrl, failureMessage, null);
  }
}
