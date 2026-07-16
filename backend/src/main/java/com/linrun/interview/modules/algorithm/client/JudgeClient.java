package com.linrun.interview.modules.algorithm.client;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;

/** 外部不可信代码执行边界；应用服务器只组装驱动，不执行用户源码。 */
public interface JudgeClient {

  String providerName();

  boolean available(CodingLanguage language);

  JudgeClientResult judge(JudgeRequest request);
}
