package com.linrun.interview.business.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.resume.analysis")
public class ResumeAnalysisProperties {

    private String systemPromptPath = "classpath:prompts/resume/analysis-system.txt";
    private String userPromptPath = "classpath:prompts/resume/analysis-user.txt";
}
