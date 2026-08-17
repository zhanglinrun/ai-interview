package com.linrun.interview.business.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.evaluation")
public class InterviewEvaluationProperties {

    private int batchSize = 3;
    private String systemPromptPath = "classpath:prompts/interview/evaluation/system.txt";
    private String userPromptPath = "classpath:prompts/interview/evaluation/user.txt";
    private String summarySystemPromptPath = "classpath:prompts/interview/evaluation/summary-system.txt";
    private String summaryUserPromptPath = "classpath:prompts/interview/evaluation/summary-user.txt";
}
