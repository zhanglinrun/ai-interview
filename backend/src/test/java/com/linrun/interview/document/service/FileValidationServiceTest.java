package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.FileValidationService;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("文件验证服务测试")
class FileValidationServiceTest {

    private FileValidationService fileValidationService;

    @BeforeEach
    void setUp() {
        fileValidationService = new FileValidationService();
    }

    @Nested
    @DisplayName("validateFile() 测试")
    class ValidateFileTests {

        @Test
        @DisplayName("文件为空时应抛出业务异常")
        void shouldRejectEmptyFile() {
            MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.pdf",
                "application/pdf",
                new byte[0]
            );

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fileValidationService.validateFile(file, 1024, "简历")
            );

            assertEquals(ErrorCode.BAD_REQUEST.getCode(), exception.getCode());
            assertEquals("请选择要上传的简历文件", exception.getMessage());
        }

        @Test
        @DisplayName("文件对象为空时应抛出业务异常")
        void shouldRejectNullFile() {
            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fileValidationService.validateFile(null, 1024, "知识库")
            );

            assertEquals(ErrorCode.BAD_REQUEST.getCode(), exception.getCode());
            assertEquals("请选择要上传的知识库文件", exception.getMessage());
        }

        @Test
        @DisplayName("文件大小超过限制时应抛出业务异常")
        void shouldRejectOversizedFile() {
            MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "content".getBytes()
            );

            BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fileValidationService.validateFile(file, 1, "简历")
            );

            assertEquals(ErrorCode.BAD_REQUEST.getCode(), exception.getCode());
            assertEquals("文件大小超过限制", exception.getMessage());
        }

        @Test
        @DisplayName("合法文件不应抛出异常")
        void shouldAcceptValidFile() {
            MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "content".getBytes()
            );

            assertDoesNotThrow(() -> fileValidationService.validateFile(file, 1024, "简历"));
        }
    }
}
