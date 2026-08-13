package com.hsmap.factverification.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/**
 * 用固定库和固定遍历顺序解析七类比赛材料。
 *
 * <p>该类只承担确定性读取，不调用模型、OCR 或外部服务；解析失败必须在 Agent 执行前关闭。
 */
public final class DeterministicDocumentParser {

    public static final String PARSER_VERSION = "deterministic-parser-1";

    private final ObjectMapper objectMapper;
    private final CanonicalJsonHasher hasher;
    private final long maxInputBytes;

    /** 注入统一 JSON 与 hash 口径，并显式限制单文件大小，避免静默截断。 */
    public DeterministicDocumentParser(ObjectMapper objectMapper, CanonicalJsonHasher hasher, long maxInputBytes) {
        this.objectMapper = objectMapper;
        this.hasher = hasher;
        this.maxInputBytes = maxInputBytes;
    }

    /** 按扩展名选择确定性解析器并生成文件、快照 SHA-256。 */
    public DocumentSnapshot parse(Path path, String fileId) {
        validateInput(path, fileId);
        String extension = extensionOf(path);
        ParsedContent content;
        try {
            content = switch (extension) {
                case "pdf" -> parsePdf(path, fileId);
                case "docx" -> parseDocx(path, fileId);
                case "doc" -> parseDoc(path, fileId);
                case "pptx" -> parsePptx(path, fileId);
                case "ppt" -> parsePpt(path, fileId);
                case "md", "markdown", "txt" -> parseLines(path, fileId, extension);
                case "xlsx", "xls" -> parseWorkbook(path, fileId);
                case "csv" -> parseCsv(path, fileId);
                default -> throw new ServiceException("DOCUMENT_TYPE_UNSUPPORTED", "不支持该材料格式");};
        } catch (ServiceException exception) {
            throw exception;
        } catch (InvalidPasswordException | EncryptedDocumentException exception) {
            throw new ServiceException("ENCRYPTED_DOCUMENT", "加密材料暂不支持解析");
        } catch (Exception exception) {
            throw new ServiceException("DOCUMENT_PARSE_FAILED", "材料损坏或无法解析");
        }
        if (content.blocks().isEmpty() && content.tables().isEmpty()) {
            String code = "pdf".equals(extension) ? "SCANNED_DOCUMENT_UNSUPPORTED" : "DOCUMENT_HAS_NO_TEXT";
            throw new ServiceException(code, "材料没有可读取文本，MVP 不提供 OCR");
        }
        String fileHash = sha256(path);
        Map<String, Object> snapshotContent = Map.of(
                "fileId", fileId,
                "parserVersion", PARSER_VERSION,
                "blocks", content.blocks(),
                "tables", content.tables(),
                "warnings", content.warnings());
        // 先通过 ObjectMapper 做一次可序列化性检查，避免返回无法持久化的快照。
        try {
            objectMapper.writeValueAsString(snapshotContent);
        } catch (IOException exception) {
            throw new ServiceException("DOCUMENT_SNAPSHOT_INVALID", "文档快照无法序列化");
        }
        return new DocumentSnapshot(
                fileId,
                PARSER_VERSION,
                fileHash,
                hasher.hash(snapshotContent),
                content.blocks(),
                content.tables(),
                content.warnings());
    }

    private void validateInput(Path path, String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new ServiceException("FILE_ID_REQUIRED", "文件识别值不能为空");
        }
        try {
            long size = Files.size(path);
            if (size <= 0) {
                throw new ServiceException("DOCUMENT_EMPTY", "材料为空");
            }
            if (size > maxInputBytes) {
                throw new ServiceException("DOCUMENT_TOO_LARGE", "材料超过当前解析上限");
            }
        } catch (IOException exception) {
            throw new ServiceException("DOCUMENT_UNREADABLE", "无法读取材料");
        }
    }

    private ParsedContent parsePdf(Path path, String fileId) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted()) {
                throw new ServiceException("ENCRYPTED_DOCUMENT", "加密材料暂不支持解析");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalizeText(stripper.getText(document));
                if (!text.isBlank()) {
                    blocks.add(new DocumentBlock("PAGE", text, DocumentLocator.page(fileId, page)));
                }
            }
        }
        return new ParsedContent(blocks, List.of(), List.of());
    }

    private ParsedContent parseDocx(Path path, String fileId) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path);
                XWPFDocument document = new XWPFDocument(input)) {
            int index = 1;
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = normalizeText(paragraph.getText());
                if (!text.isBlank()) {
                    blocks.add(new DocumentBlock("PARAGRAPH", text, DocumentLocator.paragraph(fileId, index)));
                }
                index++;
            }
        }
        return new ParsedContent(blocks, List.of(), List.of());
    }

    private ParsedContent parseDoc(Path path, String fileId) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path);
                HWPFDocument document = new HWPFDocument(input);
                WordExtractor extractor = new WordExtractor(document)) {
            String[] paragraphs = extractor.getParagraphText();
            for (int index = 0; index < paragraphs.length; index++) {
                String text = normalizeText(paragraphs[index]);
                if (!text.isBlank()) {
                    blocks.add(new DocumentBlock("PARAGRAPH", text, DocumentLocator.paragraph(fileId, index + 1)));
                }
            }
        }
        return new ParsedContent(blocks, List.of(), List.of());
    }

    private ParsedContent parsePptx(Path path, String fileId) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path);
                XMLSlideShow document = new XMLSlideShow(input)) {
            int slideNumber = 1;
            for (XSLFSlide slide : document.getSlides()) {
                int textBlock = 1;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = normalizeText(textShape.getText());
                        if (!text.isBlank()) {
                            blocks.add(new DocumentBlock(
                                    "SLIDE_TEXT", text, DocumentLocator.slide(fileId, slideNumber, textBlock)));
                        }
                        textBlock++;
                    }
                }
                slideNumber++;
            }
        }
        return new ParsedContent(blocks, List.of(), List.of());
    }

    private ParsedContent parsePpt(Path path, String fileId) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path);
                HSLFSlideShow document = new HSLFSlideShow(input)) {
            int slideNumber = 1;
            for (HSLFSlide slide : document.getSlides()) {
                int textBlock = 1;
                for (var shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String text = normalizeText(textShape.getText());
                        if (!text.isBlank()) {
                            blocks.add(new DocumentBlock(
                                    "SLIDE_TEXT", text, DocumentLocator.slide(fileId, slideNumber, textBlock)));
                        }
                        textBlock++;
                    }
                }
                slideNumber++;
            }
        }
        return new ParsedContent(blocks, List.of(), List.of());
    }

    private ParsedContent parseLines(Path path, String fileId, String extension) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> sectionPath = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String text = lines.get(index).strip();
            if (text.isBlank()) {
                continue;
            }
            if (("md".equals(extension) || "markdown".equals(extension)) && text.startsWith("#")) {
                int level = (int)
                        text.chars().takeWhile(character -> character == '#').count();
                while (sectionPath.size() >= level) {
                    sectionPath.remove(sectionPath.size() - 1);
                }
                sectionPath.add(text.substring(level).strip());
            }
            blocks.add(
                    new DocumentBlock("LINE", text, DocumentLocator.lines(fileId, index + 1, index + 1, sectionPath)));
        }
        return new ParsedContent(blocks, List.of(), List.of());
    }

    private ParsedContent parseCsv(Path path, String fileId) throws IOException {
        List<DocumentRow> rows = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
                CSVParser parser = CSVFormat.DEFAULT.builder().get().parse(reader)) {
            int rowNumber = 1;
            for (CSVRecord record : parser) {
                List<DocumentCell> cells = new ArrayList<>();
                for (int column = 0; column < record.size(); column++) {
                    String coordinate = CellReference.convertNumToColString(column) + rowNumber;
                    String value = record.get(column);
                    cells.add(new DocumentCell(coordinate, value, value, null, true));
                }
                rows.add(new DocumentRow(rowNumber, cells));
                rowNumber++;
            }
        }
        String range = rows.isEmpty() ? "A1:A1" : tableRange(rows);
        DocumentLocator locator =
                new DocumentLocator(fileId, null, List.of(), null, null, null, null, null, null, 1, rows.size());
        return new ParsedContent(List.of(), List.of(new DocumentTable("CSV", locator, rows)), List.of());
    }

    private ParsedContent parseWorkbook(Path path, String fileId) throws IOException {
        List<DocumentTable> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (InputStream input = Files.newInputStream(path);
                Workbook workbook = WorkbookFactory.create(input)) {
            for (Sheet sheet : workbook) {
                List<DocumentRow> rows = new ArrayList<>();
                int lastColumn = 0;
                for (Row row : sheet) {
                    List<DocumentCell> cells = new ArrayList<>();
                    int upperColumn = Math.max(0, row.getLastCellNum());
                    lastColumn = Math.max(lastColumn, upperColumn);
                    for (int column = 0; column < upperColumn; column++) {
                        Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        String coordinate = new CellReference(row.getRowNum(), column).formatAsString();
                        if (cell.getCellType() == CellType.FORMULA) {
                            String display = formatter.formatCellValue(cell);
                            boolean calculated = hasCachedFormulaResult(cell, display);
                            if (!calculated) {
                                warnings.add(
                                        "FORMULA_WITHOUT_CACHED_RESULT:" + sheet.getSheetName() + "!" + coordinate);
                            }
                            cells.add(new DocumentCell(
                                    coordinate,
                                    "=" + cell.getCellFormula(),
                                    display,
                                    cell.getCellFormula(),
                                    calculated));
                        } else {
                            String value = formatter.formatCellValue(cell);
                            cells.add(new DocumentCell(coordinate, value, value, null, true));
                        }
                    }
                    rows.add(new DocumentRow(row.getRowNum() + 1, cells));
                }
                if (!rows.isEmpty()) {
                    String end = CellReference.convertNumToColString(Math.max(0, lastColumn - 1))
                            + rows.get(rows.size() - 1).rowNumber();
                    DocumentLocator locator = DocumentLocator.cells(fileId, sheet.getSheetName(), "A1:" + end);
                    tables.add(new DocumentTable(sheet.getSheetName(), locator, rows));
                }
            }
        }
        return new ParsedContent(List.of(), tables, warnings);
    }

    private static String tableRange(List<DocumentRow> rows) {
        int maxColumns = rows.stream().mapToInt(row -> row.cells().size()).max().orElse(1);
        return "A1:"
                + CellReference.convertNumToColString(Math.max(0, maxColumns - 1))
                + rows.get(rows.size() - 1).rowNumber();
    }

    /**
     * OOXML 用 `<v>` 保存公式的缓存结果；只有实际存在非空值时才视为可直接核验。
     *
     * <p>旧 XLS 没有等价公开标记，只能以格式化结果是否存在作为保守判断。
     */
    private static boolean hasCachedFormulaResult(Cell cell, String display) {
        if (cell instanceof XSSFCell xssfCell) {
            return xssfCell.getCTCell().isSetV()
                    && xssfCell.getCTCell().getV() != null
                    && !xssfCell.getCTCell().getV().isBlank();
        }
        return !display.isBlank();
    }

    private static String extensionOf(Path path) {
        String fileName = path.getFileName().toString();
        int separator = fileName.lastIndexOf('.');
        return separator < 0 ? "" : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String text) {
        return text == null
                ? ""
                : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ServiceException("FILE_HASH_FAILED", "无法生成材料识别值");
        }
    }

    private record ParsedContent(List<DocumentBlock> blocks, List<DocumentTable> tables, List<String> warnings) {}
}
