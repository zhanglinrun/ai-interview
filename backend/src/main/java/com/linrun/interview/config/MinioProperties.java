package com.linrun.interview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    private String endpoint = "http://localhost:9000";
    /** 外部 MinerU 可访问的签名 endpoint；为空时沿用内部 endpoint。 */
    private String externalEndpoint = "";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private String bucket = "ai-interview";
    /** 创建桶时是否设置匿名 s3:GetObject（供视觉模型拉取图片 URL）。 */
    private boolean publicRead = true;
}
