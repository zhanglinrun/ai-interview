package com.linrun.interview.document.service.impl;
import com.linrun.interview.rag.constant.MetadataKeyConstant;


import com.linrun.interview.infra.snowflake.SnowflakeIdGenerator;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.linrun.interview.rag.constant.MetadataKeyConstant.CHUNK_ID;

/**
 * RAGFlow 风格 Excel/CSV 切块器（参考业界实现 {@code ExcelSplitter}）。
 */
@Slf4j
public class ExcelSplitter {

  public static final int DEFAULT_CHUNK_SIZE = 500;

  private final int chunkSize;
  private final boolean htmlMode;

  public ExcelSplitter(int chunkSize) {
    this(chunkSize, false);
  }

  public ExcelSplitter(int chunkSize, boolean htmlMode) {
    this.chunkSize = Math.max(64, chunkSize);
    this.htmlMode = htmlMode;
  }

  public List<TextSegment> split(byte[] fileData) throws IOException {
    FileType fileType = detectFileType(fileData);
    List<String> chunks = switch (fileType) {
      case XLSX, XLS -> parseExcel(fileData);
      case CSV -> parseCsv(fileData);
      default -> throw new IllegalArgumentException("不支持的表格文件格式");
    };
    log.info("ExcelSplitter 切块完成: mode={}, chunks={}", htmlMode ? "html" : "kv", chunks.size());
    return chunks.stream().map(text -> {
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
      return new TextSegment(text, Metadata.from(metadata));
    }).collect(Collectors.toCollection(ArrayList::new));
  }

  private List<String> parseExcel(byte[] fileData) throws IOException {
    List<List<String>> allRows = new ArrayList<>();
    try (ByteArrayInputStream bis = new ByteArrayInputStream(fileData)) {
      EasyExcel.read(bis, new ReadListener<Map<Integer, String>>() {
        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
          List<String> row = new ArrayList<>();
          int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
          for (int i = 0; i <= maxIndex; i++) {
            row.add(data.getOrDefault(i, ""));
          }
          allRows.add(row);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
        }
      }).headRowNumber(0).sheet().doRead();
    }
    return processRows(allRows);
  }

  private List<String> parseCsv(byte[] fileData) throws IOException {
    Charset charset = detectCharset(fileData);
    List<List<String>> allRows = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(new ByteArrayInputStream(fileData), charset))) {
      String line;
      while ((line = reader.readLine()) != null) {
        allRows.add(parseCsvLine(line));
      }
    }
    return processRows(allRows);
  }

  private List<String> parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (char c : line.toCharArray()) {
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == ',' && !inQuotes) {
        fields.add(current.toString().trim());
        current = new StringBuilder();
      } else {
        current.append(c);
      }
    }
    fields.add(current.toString().trim());
    return fields;
  }

  private List<String> processRows(List<List<String>> allRows) {
    if (allRows.isEmpty()) {
      return Collections.emptyList();
    }
    allRows = allRows.stream()
        .map(row -> row.stream().map(this::cleanCell).collect(Collectors.toList()))
        .collect(Collectors.toList());
    return htmlMode ? convertToHtmlChunks(allRows) : convertToKeyValuePairs(allRows);
  }

  private String cleanCell(String cell) {
    if (cell == null) {
      return "";
    }
    return cell.replaceAll("[\\x00-\\x09\\x0B-\\x0C\\x0E-\\x1F]", "");
  }

  private List<String> convertToKeyValuePairs(List<List<String>> rows) {
    List<String> result = new ArrayList<>();
    if (rows.size() < 2) {
      return result;
    }
    List<String> headers = rows.get(0);
    for (int i = 1; i < rows.size(); i++) {
      List<String> row = rows.get(i);
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < headers.size() && j < row.size(); j++) {
        String header = headers.get(j).trim();
        String value = row.get(j).trim();
        if (!header.isEmpty() || !value.isEmpty()) {
          if (!sb.isEmpty()) {
            sb.append("; ");
          }
          sb.append(header).append("：").append(value);
        }
      }
      if (!sb.isEmpty()) {
        result.add(sb.toString());
      }
    }
    return result;
  }

  private List<String> convertToHtmlChunks(List<List<String>> rows) {
    List<String> result = new ArrayList<>();
    if (rows.isEmpty()) {
      return result;
    }
    List<String> headers = rows.get(0);
    List<List<String>> dataRows = rows.subList(1, rows.size());
    List<List<String>> currentChunk = new ArrayList<>();
    int currentChunkSize = 0;
    int headerSize = calculateRowSize(headers);
    for (List<String> row : dataRows) {
      int rowSize = calculateRowSize(row);
      if (currentChunk.isEmpty()) {
        currentChunk.add(row);
        currentChunkSize = headerSize + rowSize;
      } else if (currentChunkSize + rowSize <= chunkSize) {
        currentChunk.add(row);
        currentChunkSize += rowSize;
      } else {
        result.add(buildHtmlTable(headers, currentChunk));
        currentChunk = new ArrayList<>();
        currentChunk.add(row);
        currentChunkSize = headerSize + rowSize;
      }
    }
    if (!currentChunk.isEmpty()) {
      result.add(buildHtmlTable(headers, currentChunk));
    }
    return result;
  }

  private int calculateRowSize(List<String> row) {
    int size = 15;
    for (String cell : row) {
      size += (cell != null ? cell.length() : 0) + 9;
    }
    return size;
  }

  private String buildHtmlTable(List<String> headers, List<List<String>> dataRows) {
    StringBuilder html = new StringBuilder("<table>\n  <thead>\n    <tr>\n");
    for (String header : headers) {
      html.append("      <th>").append(escapeHtml(header)).append("</th>\n");
    }
    html.append("    </tr>\n  </thead>\n  <tbody>\n");
    for (List<String> row : dataRows) {
      html.append("    <tr>\n");
      for (int i = 0; i < headers.size(); i++) {
        String value = i < row.size() ? row.get(i) : "";
        html.append("      <td>").append(escapeHtml(value)).append("</td>\n");
      }
      html.append("    </tr>\n");
    }
    html.append("  </tbody>\n</table>");
    return html.toString();
  }

  private String escapeHtml(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }

  private enum FileType {
    XLSX, XLS, CSV, UNKNOWN
  }

  private FileType detectFileType(byte[] data) {
    if (data.length < 4) {
      return FileType.UNKNOWN;
    }
    if (data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
      return FileType.XLSX;
    }
    if (data[0] == (byte) 0xD0 && data[1] == (byte) 0xCF
        && data[2] == (byte) 0x11 && data[3] == (byte) 0xE0) {
      return FileType.XLS;
    }
    String sample = new String(data, 0, Math.min(100, data.length), StandardCharsets.UTF_8);
    if (sample.contains(",") && (sample.contains("\n") || sample.contains("\r"))) {
      return FileType.CSV;
    }
    return FileType.UNKNOWN;
  }

  private Charset detectCharset(byte[] data) {
    if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
      return StandardCharsets.UTF_8;
    }
    return StandardCharsets.UTF_8;
  }
}
