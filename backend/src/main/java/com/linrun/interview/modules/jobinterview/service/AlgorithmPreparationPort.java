package com.linrun.interview.modules.jobinterview.service;

import com.linrun.interview.modules.jobinterview.model.JobCodingLanguage;

/** 算法题模块的冻结接口；Judge0/Hot100 未就绪时返回显式降级而不是伪执行。 */
public interface AlgorithmPreparationPort {

  Reservation reserve(Long userId, Long jobDescriptionId, JobCodingLanguage language);

  record Reservation(
      boolean available,
      boolean judgeAvailable,
      String problemId,
      String problemVersion,
      String question,
      String degradedReason
  ) {
    public static Reservation unavailable(String reason) {
      return new Reservation(
          false, false,
          "HOT100_TWO_SUM",
          "bootstrap-v1",
          "给定一个整数数组 nums 和目标值 target，请返回和为 target 的两个元素下标。"
              + "请先澄清输入约束，说明时间与空间复杂度，再使用当前锁定语言完成函数实现并自测。",
          reason);
    }
  }
}
