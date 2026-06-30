package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 知识库文件类型（对齐 know-engine FileType），用于选择文件解析器。
 */
public enum FileType {
    PDF("pdf"),
    DOC("doc"),
    TXT("txt"),
    HTML("html"),
    MARKDOWN("markdown"),
    CSV("csv"),
    EXCEL("excel");

    private final String type;

    FileType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
