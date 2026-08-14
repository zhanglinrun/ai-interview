package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.SpreadsheetProcessServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("表格解析服务测试")
class SpreadsheetProcessServiceTest {

    @Test
    @DisplayName("CSV 应转为 Markdown 表格和行记录")
    void csvToMarkdown() {
        SpreadsheetProcessServiceImpl service = new SpreadsheetProcessServiceImpl(mock(DocumentParseService.class));

        String markdown = service.processDocument(
            "题目,分类,答案\nJVM GC 是什么,Java,垃圾回收\n".getBytes(StandardCharsets.UTF_8),
            "questions.csv");

        assertThat(markdown).contains("| 题目 | 分类 | 答案 |");
        assertThat(markdown).contains("题目: JVM GC 是什么; 分类: Java; 答案: 垃圾回收");
    }

    @Test
    @DisplayName("CSV 引号和逗号应按单元格处理")
    void quotedCsv() {
        SpreadsheetProcessServiceImpl service = new SpreadsheetProcessServiceImpl(mock(DocumentParseService.class));

        String markdown = service.processDocument(
            "公司,备注\n\"A,B\",\"一面, 技术\"\n".getBytes(StandardCharsets.UTF_8),
            "schedule.csv");

        assertThat(markdown).contains("| A,B | 一面, 技术 |");
    }

    @Test
    @DisplayName("XLSX 应按 Sheet 行列转为表格和行记录")
    void xlsxToMarkdown() throws IOException {
        SpreadsheetProcessServiceImpl service = new SpreadsheetProcessServiceImpl(mock(DocumentParseService.class));

        String markdown = service.processDocument(xlsxBytes(), "questions.xlsx");

        assertThat(markdown).contains("## Java题库");
        assertThat(markdown).contains("| 题目 | 分类 | 答案 |");
        assertThat(markdown).contains("题目: volatile 作用; 分类: Java; 答案: 可见性");
    }

    private byte[] xlsxBytes() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Java题库");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("题目");
            header.createCell(1).setCellValue("分类");
            header.createCell(2).setCellValue("答案");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("volatile 作用");
            row.createCell(1).setCellValue("Java");
            row.createCell(2).setCellValue("可见性");
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }
}
