package com.linrun.interview.modules.knowledgebase.service.parse;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infrastructure.file.DocumentParseService;
import com.linrun.interview.modules.knowledgebase.config.MineruProperties;
import com.linrun.interview.modules.knowledgebase.constant.FileType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MinerU 文件解析器（对齐 know-engine MinerUProcessBaseServiceImpl / PdfProcessServiceImpl）。
 *
 * <p>处理 PDF/DOC/HTML 等结构化文档：调外部 MinerU HTTP 服务 {@code /file_parse}，把文档转换为
 * Markdown（保留标题层级、表格、公式）。{@link MineruProperties#isEnabled()} 关闭或调用失败时，
 * 降级到 {@link DocumentParseService}（Tika 纯文本），保证解析链路始终可用。
 *
 * <p>与 know-engine 实现的差异（遵守 ai-interview AGENTS.md）：
 * <ul>
 *   <li>用 Spring {@link RestClient}（已可用）替代 Apache HttpClient 5，不引入新依赖。</li>
 *   <li>配置集中到 {@link MineruProperties}（{@code @ConfigurationProperties}），不散落 {@code @Value}。</li>
 *   <li>失败抛 {@link BusinessException}，不用 {@code RuntimeException}。</li>
 *   <li>MinerU 不可达时 fallback Tika，而非直接失败。</li>
 * </ul>
 */
@Slf4j
@Service
@EnableConfigurationProperties(MineruProperties.class)
public class MineruProcessService implements FileProcessService {

    private final MineruProperties properties;
    private final DocumentParseService tikaParseService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MineruProcessService(MineruProperties properties, DocumentParseService tikaParseService) {
        this.properties = properties;
        this.tikaParseService = tikaParseService;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder()
            .baseUrl(properties.getApiUrl())
            .requestFactory(requestFactory)
            .build();
    }

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.PDF || fileType == FileType.DOC
            || fileType == FileType.HTML;
    }

    @Override
    public String processDocument(byte[] fileBytes, String fileName) {
        if (!properties.isEnabled()) {
            log.debug("MinerU 未启用，降级 Tika 解析: fileName={}", fileName);
            return tikaParseService.parseContent(fileBytes, fileName);
        }
        try {
            String markdown = callMineru(fileBytes, fileName);
            if (markdown == null || markdown.isBlank()) {
                log.warn("MinerU 返回空内容，降级 Tika 解析: fileName={}", fileName);
                return tikaParseService.parseContent(fileBytes, fileName);
            }
            log.info("MinerU 解析成功: fileName={}, markdownChars={}", fileName, markdown.length());
            return markdown;
        } catch (Exception e) {
            log.warn("MinerU 解析失败，降级 Tika 解析: fileName={}, error={}", fileName, e.getMessage());
            return tikaParseService.parseContent(fileBytes, fileName);
        }
    }

    /**
     * 调 MinerU /file_parse，发 multipart（files=文件 + backend + return_images），
     * 解析 JSON 响应取 results.{docTitle}.md_content。
     */
    private String callMineru(byte[] fileBytes, String fileName) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        form.add("backend", properties.getBackend());
        form.add("return_images", String.valueOf(properties.isReturnImages()));

        String response = restClient.post()
            .uri(properties.getParsePath())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .body(String.class);

        return extractMarkdown(response, fileName);
    }

    /**
     * 从 MinerU JSON 响应提取 Markdown 文本。
     * 响应结构：{@code { "results": { "<docTitle>": { "md_content": "..." } } }}。
     */
    private String extractMarkdown(String response, String fileName) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isMissingNode() || !results.isObject()) {
                log.warn("MinerU 响应无 results 字段: fileName={}", fileName);
                return null;
            }
            // 取第一个文档的 md_content（results 下按 docTitle 分组）
            JsonNode firstDoc = results.elements().next();
            if (firstDoc == null || firstDoc.isMissingNode()) {
                return null;
            }
            String md = firstDoc.path("md_content").asText("");
            return md.isBlank() ? null : md;
        } catch (Exception e) {
            log.warn("解析 MinerU 响应 JSON 失败: fileName={}, error={}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * MultipartFile 入参重载（供上传流程直接传 MultipartFile）。
     */
    public String processDocument(MultipartFile file) {
        try {
            return processDocument(file.getBytes(), file.getOriginalFilename());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * MinerU 是否启用且可用（供编排层决策日志）。
     */
    public boolean isMineruEnabled() {
        return properties.isEnabled();
    }
}
