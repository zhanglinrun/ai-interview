package com.linrun.interview.modules.knowledgebase.service.parse.mineru;

/** MinerU 适配器的受检异常，禁止把 Token、预签名 URL 或响应正文放进 message。 */
public class MineruClientException extends Exception {

  private final MineruFailureCode failureCode;

  public MineruClientException(MineruFailureCode failureCode, String message) {
    super(message);
    this.failureCode = failureCode;
  }

  public MineruClientException(
      MineruFailureCode failureCode,
      String message,
      Throwable cause
  ) {
    super(message, cause);
    this.failureCode = failureCode;
  }

  public MineruFailureCode failureCode() {
    return failureCode;
  }
}
