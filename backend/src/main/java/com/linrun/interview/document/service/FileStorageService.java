package com.linrun.interview.document.service;

import com.linrun.interview.config.MinioProperties;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public String uploadResume(MultipartFile file) {
        return uploadFile(file, "resumes");
    }

    public void deleteResume(String fileKey) {
        deleteFile(fileKey);
    }

    public String uploadKnowledgeBase(MultipartFile file) {
        return uploadFile(file, "knowledgebases");
    }

    public void deleteKnowledgeBase(String fileKey) {
        deleteFile(fileKey);
    }

    public byte[] downloadFile(String fileKey) {
        if (!fileExists(fileKey)) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在: " + fileKey);
        }
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
            .bucket(minioProperties.getBucket())
            .object(fileKey)
            .build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            log.error("下载文件失败: {}", fileKey, e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为外部解析服务生成短时只读 URL。桶仍保持私有，不开启匿名读或列表权限。
     */
    public URI presignDownload(String fileKey, Duration ttl) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储键不能为空");
        }
        if (!fileExists(fileKey)) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件不存在");
        }
        long seconds = ttl == null ? 600L : ttl.toSeconds();
        if (seconds < 1 || seconds > 604800) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "预签名有效期必须在 1 秒到 7 天之间");
        }
        String externalEndpoint = minioProperties.getExternalEndpoint();
        if (externalEndpoint == null || externalEndpoint.isBlank()) {
            externalEndpoint = minioProperties.getEndpoint();
        }
        try {
            MinioClient signingClient = MinioClient.builder()
                .endpoint(externalEndpoint)
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
            String signed = signingClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.getBucket())
                    .object(fileKey)
                    .expiry((int) seconds)
                    .build());
            return URI.create(signed);
        } catch (Exception e) {
            log.warn("生成外部短时下载地址失败: objectKey={}, error={}", fileKey, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.STORAGE_DOWNLOAD_FAILED, "生成外部短时下载地址失败", e);
        }
    }

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private String uploadFile(MultipartFile file, String prefix) {
        String originalFilename = file.getOriginalFilename();
        String fileKey = generateFileKey(originalFilename, prefix);
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(fileKey)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
            log.info("文件上传成功: {} -> {}", originalFilename, fileKey);
            return fileKey;
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件读取失败", e);
        } catch (Exception e) {
            log.error("上传文件到 MinIO 失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败: " + e.getMessage(), e);
        }
    }

    public boolean fileExists(String fileKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(fileKey)
                .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            log.warn("检查文件存在性失败: {} - {}", fileKey, e.getMessage(), e);
            return false;
        }
    }

    public long getFileSize(String fileKey) {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(fileKey)
                .build()).size();
        } catch (Exception e) {
            log.error("获取文件大小失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取文件信息失败", e);
        }
    }

    private void deleteFile(String fileKey) {
        if (fileKey == null || fileKey.isEmpty()) {
            return;
        }
        if (!fileExists(fileKey)) {
            log.warn("文件不存在，跳过删除: {}", fileKey);
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(fileKey)
                .build());
            log.info("文件删除成功: {}", fileKey);
        } catch (Exception e) {
            log.error("删除文件失败: {} - {}", fileKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败: " + e.getMessage(), e);
        }
    }

    public String getFileUrl(String fileKey) {
        return String.format("%s/%s/%s", minioProperties.getEndpoint(), minioProperties.getBucket(), fileKey);
    }

    public void ensureBucketExists() {
        try {
            String bucket = minioProperties.getBucket();
            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("存储桶创建成功: {}", bucket);
            }
        } catch (Exception e) {
            log.error("检查/创建存储桶失败: {}", e.getMessage(), e);
        }
    }

    private String generateFileKey(String originalFilename, String prefix) {
        LocalDateTime now = LocalDateTime.now();
        String datePath = now.format(DATE_PATH_FORMAT);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String safeName = sanitizeFilename(originalFilename);
        return String.format("%s/%s/%s_%s", prefix, datePath, uuid, safeName);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown";
        }
        return convertToPinyin(filename);
    }

    private String convertToPinyin(String input) {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        StringBuilder result = new StringBuilder();
        for (char ch : input.toCharArray()) {
            try {
                String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(ch, format);
                if (pinyins != null && pinyins.length > 0) {
                    result.append(capitalize(pinyins[0]));
                } else {
                    result.append(sanitizeChar(ch));
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                result.append(sanitizeChar(ch));
            }
        }
        return result.toString();
    }

    private char sanitizeChar(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') {
            return ch;
        }
        return '_';
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
