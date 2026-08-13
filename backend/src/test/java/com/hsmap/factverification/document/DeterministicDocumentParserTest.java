package com.hsmap.factverification.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.sl.usermodel.TextBox;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证七类授权材料都形成确定性快照并保留可回到原文的位置。
 *
 * <p>Office/PDF 文件由测试即时生成，仓库只保存可审查的最小文本 fixture，避免提交来源不明的二进制材料。
 */
class DeterministicDocumentParserTest {

    @TempDir
    Path tempDir;

    private DeterministicDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new DeterministicDocumentParser(
                new ObjectMapper(), new CanonicalJsonHasher(new ObjectMapper()), 2_000_000);
    }

    /** Markdown、纯文本和 CSV 必须按行定位，CSV 同时保留结构化单元格。 */
    @Test
    void parsesLineBasedDocumentsWithLocators() throws Exception {
        Path fixtures = Path.of("src/test/resources/fixtures/documents");

        DocumentSnapshot markdown = parser.parse(fixtures.resolve("company-note.md"), "md-file");
        DocumentSnapshot text = parser.parse(fixtures.resolve("company-note.txt"), "txt-file");
        DocumentSnapshot csv = parser.parse(fixtures.resolve("company-data.csv"), "csv-file");

        assertThat(markdown.blocks())
                .anySatisfy(block -> assertThat(block.locator().lineStart()).isEqualTo(1));
        assertThat(text.blocks())
                .anySatisfy(block -> assertThat(block.locator().lineStart()).isEqualTo(1));
        assertThat(csv.tables()).singleElement().satisfies(table -> {
            assertThat(table.locator().lineStart()).isEqualTo(1);
            assertThat(table.rows()).hasSize(2);
        });
    }

    /** PDF、Word 与 PowerPoint 分别保留页码、段落号和幻灯片号。 */
    @Test
    void parsesPdfWordAndPowerPointWithNativeLocators() throws Exception {
        Path pdf = createPdf("Huoshi Technology 2025 revenue 1000");
        Path docx = createDocx("火石科技拥有软件著作权 12 项");
        Path pptx = createPptx("火石科技企业介绍");

        assertThat(parser.parse(pdf, "pdf-file").blocks().get(0).locator().page())
                .isEqualTo(1);
        assertThat(parser.parse(docx, "word-file").blocks().get(0).locator().paragraph())
                .isEqualTo(1);
        assertThat(parser.parse(pptx, "ppt-file").blocks().get(0).locator().slide())
                .isEqualTo(1);
    }

    /** Excel 保留 sheet、单元格范围、公式和缓存值状态，不在 MVP 中实现公式计算引擎。 */
    @Test
    void parsesExcelAsStructuredTableAndPreservesFormula() throws Exception {
        Path workbook = createWorkbook();

        DocumentSnapshot snapshot = parser.parse(workbook, "excel-file");

        assertThat(snapshot.tables()).singleElement().satisfies(table -> {
            assertThat(table.locator().sheet()).isEqualTo("财务");
            assertThat(table.locator().cellRange()).isEqualTo("A1:C2");
            assertThat(table.rows().get(1).cells().get(2).formula()).isEqualTo("B2*2");
            assertThat(table.rows().get(1).cells().get(2).calculated()).isFalse();
        });
    }

    /** 加密、损坏和无文本扫描型 PDF 必须在模型调用前以稳定错误码关闭。 */
    @Test
    void failsClosedForEncryptedCorruptAndScannedPdf() throws Exception {
        Path encrypted = createEncryptedPdf();
        Path corrupt = tempDir.resolve("corrupt.pdf");
        Files.writeString(corrupt, "not-a-pdf");
        Path scanned = createBlankPdf();

        assertThatThrownBy(() -> parser.parse(encrypted, "encrypted"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("ENCRYPTED_DOCUMENT");
        assertThatThrownBy(() -> parser.parse(corrupt, "corrupt"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DOCUMENT_PARSE_FAILED");
        assertThatThrownBy(() -> parser.parse(scanned, "scanned"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SCANNED_DOCUMENT_UNSUPPORTED");
    }

    /** 同一文件重复解析得到相同文件 hash 与快照 hash。 */
    @Test
    void producesDeterministicHashes() throws Exception {
        Path file = Path.of("src/test/resources/fixtures/documents/company-note.txt");

        DocumentSnapshot first = parser.parse(file, "same-file");
        DocumentSnapshot second = parser.parse(file, "same-file");

        assertThat(first.fileHash()).isEqualTo(second.fileHash()).matches("[0-9a-f]{64}");
        assertThat(first.snapshotHash()).isEqualTo(second.snapshotHash()).matches("[0-9a-f]{64}");
    }

    private Path createPdf(String text) throws Exception {
        Path path = tempDir.resolve("sample.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
        return path;
    }

    private Path createBlankPdf() throws Exception {
        Path path = tempDir.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
        return path;
    }

    private Path createEncryptedPdf() throws Exception {
        Path source = createPdf("protected");
        Path encrypted = tempDir.resolve("encrypted.pdf");
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(source.toFile())) {
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(encrypted.toFile());
        }
        return encrypted;
    }

    private Path createDocx(String text) throws Exception {
        Path path = tempDir.resolve("sample.docx");
        try (XWPFDocument document = new XWPFDocument();
                OutputStream output = Files.newOutputStream(path)) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(text);
            document.write(output);
        }
        return path;
    }

    private Path createPptx(String text) throws Exception {
        Path path = tempDir.resolve("sample.pptx");
        try (XMLSlideShow document = new XMLSlideShow();
                OutputStream output = Files.newOutputStream(path)) {
            XSLFSlide slide = document.createSlide();
            TextBox<?, ?> box = slide.createTextBox();
            box.setText(text);
            document.write(output);
        }
        return path;
    }

    private Path createWorkbook() throws Exception {
        Path path = tempDir.resolve("sample.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("财务");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("企业");
            header.createCell(1).setCellValue("收入");
            header.createCell(2).setCellValue("收入翻倍");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("火石科技");
            data.createCell(1).setCellValue(1000);
            Cell formula = data.createCell(2);
            formula.setCellFormula("B2*2");
            workbook.write(output);
        }
        return path;
    }
}
