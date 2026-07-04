package com.linrun.interview.modules.knowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinerU 文件解析配置（对齐业界实践 file.parse 配置）。
 *
 * <p>MinerU 是外部 HTTP 解析服务，把 PDF/DOC/HTML 等结构化文档转换为 Markdown（保留标题层级、
 * 表格、公式）。{@link #enabled} 关闭或服务不可达时，解析链路降级到 Tika（纯文本）。
 */
@Data
@ConfigurationProperties(prefix = "file.parse.mineru")
public class MineruProperties {

    /** 是否启用 MinerU 解析（false 或服务不可达时降级 Tika）。 */
    private boolean enabled = false;

    /** MinerU 服务地址，如 http://localhost:8000。 */
    private String apiUrl = "http://localhost:8000";

    /** 解析端点路径（相对 apiUrl）。 */
    private String parsePath = "/file_parse";

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 10000;

    /** 读取超时（毫秒）。 */
    private int readTimeoutMs = 120000;

    /** 是否返回 ZIP（含图片资源）；false 时返回 JSON（仅 Markdown 文本）。 */
    private boolean returnImages = false;

    /** 解析后端引擎（MinerU 参数）。 */
    private String backend = "pipeline";

    /** 视觉模型名（用于图片描述生成，预留，暂未启用）。 */
    private String visionModel = "qwen-vl-plus";
}
