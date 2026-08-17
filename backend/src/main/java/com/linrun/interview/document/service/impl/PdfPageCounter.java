package com.linrun.interview.document.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayInputStream;

/** 只数页，不抽正文。失败返回 -1，由调用方按 200 页窗口继续切。 */
public final class PdfPageCounter {

  private PdfPageCounter() {
  }

  public static int count(byte[] pdf) {
    if (pdf == null || pdf.length < 5) {
      return -1;
    }
    try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
      return document.getNumberOfPages();
    } catch (Exception ignored) {
      return -1;
    }
  }
}
