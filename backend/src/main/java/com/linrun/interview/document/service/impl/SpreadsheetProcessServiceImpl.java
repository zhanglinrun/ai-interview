package com.linrun.interview.document.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.read.metadata.ReadSheet;
import com.alibaba.excel.ExcelReader;
import com.linrun.interview.document.service.DocumentParseService;
import com.linrun.interview.document.service.FileProcessService;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表格文件解析器：CSV/TSV/Excel 保留行列结构，输出 Markdown 表格和行记录。
 *
 * <p>Excel 解析对齐业界实践 {@code ExcelProcessServiceImpl#parseExcel}：
 * 使用 EasyExcel + {@code headRowNumber(0)}，从第一行开始读取数据（含表头行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpreadsheetProcessServiceImpl implements FileProcessService {

    private static final int MAX_MARKDOWN_ROWS = 200;

    private final DocumentParseService documentParseService;

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.CSV || fileType == FileType.EXCEL;
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        if (knowledgeBaseType == KnowledgeBaseType.DATA_QUERY) {
            return false;
        }
        return supports(fileType);
    }

    @Override
    public String processDocument(byte[] fileBytes, String fileName) {
        return parse(fileBytes, fileName).markdown();
    }

    public ParsedSpreadsheet parse(byte[] fileBytes, String fileName) {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        if (lowerName.endsWith(".csv") || lowerName.endsWith(".tsv")) {
            return parseDelimited(fileBytes, lowerName.endsWith(".tsv") ? '\t' : ',');
        }
        try {
            return parseWorkbook(fileBytes);
        } catch (Exception e) {
            log.warn("Excel 行列解析失败，降级 Tika 文本解析: fileName={}", fileName, e);
            String text = documentParseService.parseContent(fileBytes, fileName);
            String markdown = text == null || text.isBlank() ? "" : "# 表格内容\n\n" + text.trim();
            return new ParsedSpreadsheet(markdown, List.of(), List.of());
        }
    }

    private ParsedSpreadsheet parseWorkbook(byte[] fileBytes) throws IOException {
        try (ExcelReader excelReader = EasyExcel.read(new ByteArrayInputStream(fileBytes)).build()) {
            List<ReadSheet> sheets = excelReader.excelExecutor().sheetList();
            if (sheets.isEmpty()) {
                return new ParsedSpreadsheet("", List.of(), List.of());
            }
            StringBuilder markdown = new StringBuilder("# 表格内容\n\n");
            List<List<String>> firstRows = List.of();
            int parsedSheets = 0;
            for (ReadSheet readSheet : sheets) {
                List<List<String>> rows = parseExcelSheet(fileBytes, readSheet.getSheetNo());
                if (rows.isEmpty()) {
                    continue;
                }
                if (firstRows.isEmpty()) {
                    firstRows = rows;
                }
                if (parsedSheets > 0) {
                    markdown.append('\n');
                }
                String sheetName = readSheet.getSheetName();
                markdown.append("## ")
                    .append(escapeHeading(sheetName == null || sheetName.isBlank() ? "Sheet" : sheetName))
                    .append("\n\n");
                appendRows(markdown, rows);
                parsedSheets++;
            }
            return parsedSheets == 0
                ? new ParsedSpreadsheet("", List.of(), List.of())
                : toParsed(markdown.toString(), firstRows);
        }
    }

    /**
     * 对齐业界实践：headRowNumber(0) 从第一行开始读，表头也作为数据行返回。
     */
    private List<List<String>> parseExcelSheet(byte[] fileBytes, int sheetNo) {
        List<List<String>> result = new ArrayList<>();
        EasyExcel.read(new ByteArrayInputStream(fileBytes), new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                List<String> row = new ArrayList<>();
                int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
                for (int i = 0; i <= maxIndex; i++) {
                    String value = data.getOrDefault(i, "");
                    row.add(cleanCell(value != null ? value : ""));
                }
                addRow(result, trimTrailingBlankCells(row));
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.debug("Excel sheet {} 解析完成，共 {} 行", sheetNo, result.size());
            }
        }).headRowNumber(0).sheet(sheetNo).doRead();
        return result;
    }

    private ParsedSpreadsheet parseDelimited(byte[] fileBytes, char delimiter) {
        String text = decode(fileBytes);
        List<List<String>> rows = parseRows(text, delimiter);
        if (rows.isEmpty()) {
            return new ParsedSpreadsheet("", List.of(), List.of());
        }
        StringBuilder markdown = new StringBuilder("# 表格内容\n\n");
        appendRows(markdown, rows);
        return toParsed(markdown.toString(), rows);
    }

    private ParsedSpreadsheet toParsed(String markdown, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ParsedSpreadsheet(markdown, List.of(), List.of());
        }
        List<String> headers = rows.getFirst();
        List<List<String>> dataRows = rows.stream().skip(1).toList();
        return new ParsedSpreadsheet(markdown, headers, dataRows);
    }

    private void appendRows(StringBuilder markdown, List<List<String>> rows) {
        List<List<String>> limitedRows = rows.stream().limit(MAX_MARKDOWN_ROWS).toList();
        markdown.append(toMarkdownTable(limitedRows));
        markdown.append("\n\n### 行记录\n\n");
        appendRecords(markdown, rows);
        if (rows.size() > MAX_MARKDOWN_ROWS) {
            markdown.append("\n> 仅展示前 ").append(MAX_MARKDOWN_ROWS)
                .append(" 行表格，行记录保留全部数据。\n");
        }
    }

    private void appendRecords(StringBuilder markdown, List<List<String>> rows) {
        List<String> headers = rows.getFirst();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            markdown.append("- ");
            for (int j = 0; j < row.size(); j++) {
                String key = j < headers.size() && !headers.get(j).isBlank()
                    ? headers.get(j)
                    : "列" + (j + 1);
                if (j > 0) {
                    markdown.append("; ");
                }
                markdown.append(key).append(": ").append(row.get(j));
            }
            markdown.append('\n');
        }
    }

    private String decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            text = new String(bytes, Charset.forName("GB18030"));
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private List<List<String>> parseRows(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                addRow(rows, row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString().trim());
        addRow(rows, row);
        return rows;
    }

    private void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(cell -> cell != null && !cell.isBlank())) {
            rows.add(row);
        }
    }

    private List<String> trimTrailingBlankCells(List<String> row) {
        int end = row.size();
        while (end > 0 && row.get(end - 1).isBlank()) {
            end--;
        }
        return new ArrayList<>(row.subList(0, end));
    }

    private String cleanCell(String cell) {
        return cell == null ? "" : cell.replaceAll("[\\x00-\\x09\\x0B-\\x0C\\x0E-\\x1F]", "").trim();
    }

    private String escapeHeading(String heading) {
        return heading == null || heading.isBlank() ? "Sheet" : heading.replace("#", "\\#");
    }

    private String toMarkdownTable(List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendTableRow(sb, pad(rows.getFirst(), columns));
        appendTableRow(sb, java.util.Collections.nCopies(columns, "---"));
        for (int i = 1; i < rows.size(); i++) {
            appendTableRow(sb, pad(rows.get(i), columns));
        }
        return sb.toString();
    }

    private List<String> pad(List<String> row, int columns) {
        List<String> padded = new ArrayList<>(row);
        while (padded.size() < columns) {
            padded.add("");
        }
        return padded;
    }

    private void appendTableRow(StringBuilder sb, List<String> cells) {
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(cell.replace("\r", " ").replace("\n", " ").replace("|", "\\|"))
                .append(" |");
        }
        sb.append('\n');
    }

    public record ParsedSpreadsheet(
        String markdown,
        List<String> headers,
        List<List<String>> rows
    ) {}
}
