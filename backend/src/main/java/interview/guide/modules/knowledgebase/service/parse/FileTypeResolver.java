package interview.guide.modules.knowledgebase.service.parse;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.constant.FileType;
import org.springframework.stereotype.Component;

/**
 * 文件类型解析器：从文件名/内容类型推断 {@link FileType}，供 {@link FileProcessServiceFactory} 选解析器。
 */
@Component
public class FileTypeResolver {

    /**
     * 按文件名扩展名 + 内容类型综合推断文件类型。
     *
     * @param fileName    原始文件名
     * @param contentType 内容类型（可为 null）
     * @throws BusinessException 无法识别时
     */
    public FileType resolve(String fileName, String contentType) {
        String ext = extractExtension(fileName);
        String lowerCt = contentType == null ? "" : contentType.toLowerCase();

        if (isPdf(ext, lowerCt)) {
            return FileType.PDF;
        }
        if (isWord(ext, lowerCt)) {
            return FileType.DOC;
        }
        if (isMarkdown(ext, lowerCt)) {
            return FileType.MARKDOWN;
        }
        if (isExcel(ext, lowerCt)) {
            return FileType.EXCEL;
        }
        if (isCsv(ext, lowerCt)) {
            return FileType.CSV;
        }
        if (isHtml(ext, lowerCt)) {
            return FileType.HTML;
        }
        if (isText(ext, lowerCt)) {
            return FileType.TXT;
        }
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
            "不支持的文件类型: fileName=" + fileName + ", contentType=" + contentType);
    }

    private String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private boolean isPdf(String ext, String ct) {
        return "pdf".equals(ext) || ct.contains("pdf");
    }

    private boolean isWord(String ext, String ct) {
        return "doc".equals(ext) || "docx".equals(ext)
            || ct.contains("msword") || ct.contains("wordprocessingml");
    }

    private boolean isMarkdown(String ext, String ct) {
        return "md".equals(ext) || "markdown".equals(ext) || "mdown".equals(ext)
            || ct.contains("markdown");
    }

    private boolean isExcel(String ext, String ct) {
        return "xls".equals(ext) || "xlsx".equals(ext)
            || ct.contains("spreadsheet") || ct.contains("excel");
    }

    private boolean isCsv(String ext, String ct) {
        return "csv".equals(ext) || ct.contains("csv");
    }

    private boolean isHtml(String ext, String ct) {
        return "html".equals(ext) || "htm".equals(ext) || ct.contains("html");
    }

    private boolean isText(String ext, String ct) {
        return "txt".equals(ext) || "text".equals(ext)
            || ct.contains("text/plain") || ct.contains("text/x-markdown");
    }
}
