package com.linrun.interview.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** EchoMind 风格评测质量门槛；所有门槛都可按环境或数据集覆盖。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag-evaluation")
public class EvalQualityProperties {
    private double intentAccuracy = 0.90;
    private double intentMacroF1 = 0.85;
    private double retrievalRecall = 0.85;
    private double retrievalMrr = 0.75;
    private double retrievalNdcg = 0.75;
    private double citationCoverage = 0.90;
    private double groundedness = 0.80;
    private double answerQuality = 0.75;
    private double regressionThreshold = 0.05;
}
