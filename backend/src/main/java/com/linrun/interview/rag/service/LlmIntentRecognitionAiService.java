package com.linrun.interview.rag.service;

import com.linrun.interview.rag.model.LlmIntentRecognitionResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LLM 单路语义意图识别 AiServices 接口。
 */
public interface LlmIntentRecognitionAiService {

  @SystemMessage(fromResource = "prompts/rag/intent-recognition.txt")
  @UserMessage("{{it}}")
  LlmIntentRecognitionResult recognize(String questionWithHistory);
}
