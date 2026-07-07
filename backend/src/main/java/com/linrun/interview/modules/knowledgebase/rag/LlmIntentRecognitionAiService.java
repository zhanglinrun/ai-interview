package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LLM 单路语义意图识别 AiServices 接口。
 */
public interface LlmIntentRecognitionAiService {

  @SystemMessage(fromResource = "prompts/intent-recognition.st")
  @UserMessage("{{it}}")
  LlmIntentRecognitionResult recognize(String questionWithHistory);
}
