package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.PdfPageCounter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PDF 页数")
class PdfPageCounterTest {

  @Test
  @DisplayName("能数出真实页数，坏字节返回 -1")
  void countsPagesOrUnknown() throws Exception {
    assertThat(PdfPageCounter.count("not-a-pdf".getBytes())).isEqualTo(-1);
    try (PDDocument document = new PDDocument();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      document.addPage(new PDPage());
      document.save(output);
      assertThat(PdfPageCounter.count(output.toByteArray())).isEqualTo(2);
    }
  }
}
