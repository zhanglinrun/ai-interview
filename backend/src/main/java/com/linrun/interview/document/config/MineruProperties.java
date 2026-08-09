package com.linrun.interview.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinerU 官方异步精准解析 API 配置。
 *
 * <p>原始文件始终保存在私有 MinIO；调用时生成短时预签名 URL，提交官方任务并有界轮询。
 * 任何失败都显式记录后降级到 Tika。
 */
@Data
@ConfigurationProperties(prefix = "file.parse.mineru")
public class MineruProperties {

    /** 默认启用；Token 未配置时会记录 CONFIGURATION 并显式降级。 */
    private boolean enabled = true;

    /** MinerU 官方 API 地址。 */
    private String baseUrl = "https://mineru.net";

    /** Bearer Token，只能来自环境变量。 */
    private String apiToken = "";

    private String submitPath = "/api/v4/extract/task";

    private String statusPath = "/api/v4/extract/task/{taskId}";

    /** PDF / Word 默认精准解析模型。 */
    private String modelVersion = "vlm";

    /** HTML 固定解析模型。 */
    private String htmlModelVersion = "MinerU-HTML";

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 10000;

    /** 单次 HTTP 请求超时（毫秒），总任务超时由 taskTimeoutSeconds 控制。 */
    private int requestTimeoutMs = 30000;

    /** 轮询间隔。 */
    private long pollIntervalMs = 2000;

    /** 单次解析任务总超时。 */
    private long taskTimeoutSeconds = 300;

    /** MinIO 预签名下载 URL 有效期。 */
    private int presignedUrlTtlSeconds = 600;

    /** ZIP 下载上限。 */
    private long maxZipBytes = 100L * 1024 * 1024;

    /** submit/status JSON 响应上限。 */
    private long maxJsonResponseBytes = 1024L * 1024;

    /** ZIP 解压后所有 entry 的总上限。 */
    private long maxUncompressedBytes = 200L * 1024 * 1024;

    /** ZIP entry 数上限。 */
    private int maxZipEntries = 10000;

    /** 解压总量 / ZIP 大小的最大比例。 */
    private double maxCompressionRatio = 100.0d;

    /** full.md 最大正文大小。 */
    private long maxMarkdownBytes = 20L * 1024 * 1024;

    /** 仅测试/内网部署可开启；生产结果下载默认拒绝环回和私网地址。 */
    private boolean allowPrivateResultUrls = false;

    /** 仅 Mock 联调可开启；云 MinerU 无法访问环回和私网 MinIO 地址。 */
    private boolean allowPrivateSourceUrls = false;

    /** 应用重启后补偿停留在 SUBMITTED/POLLING 的任务。 */
    private boolean compensationEnabled = true;

    /** 多久未更新才由补偿任务接管，避免与前台轮询竞争。 */
    private long compensationStaleSeconds = 30;
}
