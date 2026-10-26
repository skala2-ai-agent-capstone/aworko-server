package com.aworko.server.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {

    final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void readsUtf8AndKoreanWindowsText() {
        assertThat(extractor.extract("a.txt", "text/plain", "\uFEFF가나다 SSO".getBytes(StandardCharsets.UTF_8))).isEqualTo("가나다 SSO");
        assertThat(extractor.extract("a.csv", null, "가나다,1".getBytes(Charset.forName("MS949")))).isEqualTo("가나다,1");
        assertThat(extractor.extract("a.txt", "text/plain", "a\0b".getBytes(StandardCharsets.UTF_8))).isEqualTo("ab");
    }

    @Test
    void readsPdfDocxAndXlsx() throws Exception {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("REQ-01 SAML SSO");
                cs.endText();
            }
            doc.save(pdf);
        }
        assertThat(extractor.extract("rfp.PDF", "application/pdf", pdf.toByteArray())).contains("REQ-01 SAML SSO");

        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("국내 리전 필수");
            doc.write(docx);
        }
        assertThat(extractor.extract("회의록.docx", null, docx.toByteArray())).contains("국내 리전 필수");

        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            book.createSheet("가격").createRow(0).createCell(0).setCellValue("단가 25000");
            book.write(xlsx);
        }
        assertThat(extractor.extract("가격.xlsx", null, xlsx.toByteArray())).contains("단가 25000");
    }

    @Test
    void unsupportedOrBrokenFilesGiveEmptyText() {
        assertThat(extractor.extract("scan.hwp", "application/x-hwp", new byte[]{1, 2, 3})).isEmpty();
        assertThat(extractor.extract("photo.png", "image/png", new byte[]{1, 2, 3})).isEmpty();
        assertThat(extractor.extract("broken.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(extractor.extract("broken.docx", null, "not a zip".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
