package com.linrun.interview.business.client;

import com.linrun.interview.business.constant.CodingLanguage;

/** Judge0 关闭时的本地降级客户端，保留待补判语义且绝不访问网络。 */
public class UnavailableJudgeClient implements JudgeClient {

  @Override
  public String providerName() {
    return "JUDGE0_DISABLED";
  }

  @Override
  public boolean available(CodingLanguage language) {
    return false;
  }

  @Override
  public JudgeClientResult judge(JudgeRequest request) {
    return JudgeClientResult.unavailable(
        request.totalCount(), "JUDGE_NOT_CONFIGURED", "判题服务尚未配置，可稍后补判");
  }
}
