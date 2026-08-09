package com.linrun.interview.business.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {

    private int followUpCount = 1;
    private String questionSystemPromptPath = "classpath:prompts/interview/question/topic-system.txt";
    private String questionUserPromptPath = "classpath:prompts/interview/question/topic-user.txt";
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview/question/resume-system.txt";
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview/question/resume-user.txt";
}
