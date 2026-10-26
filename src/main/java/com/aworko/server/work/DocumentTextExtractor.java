package com.aworko.server.work;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.springframework.stereotype.Component;

/**
 * 올린 고객 자료에서 텍스트를 꺼낸다. 텍스트 파일(UTF-8, 아니면 MS949)·PDF·DOCX·XLSX·PPTX를 읽고,
 * 그 밖의 형식(이미지·HWP 등)이나 깨진 파일은 빈 문자열을 돌려준다(원본 파일은 따로 저장된다).
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    static final int MAX_CHARS = 1_000_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log");
    private static final Set<String> OFFICE_EXTENSIONS = Set.of("docx", "xlsx", "pptx");

    public String extract(String fileName, String contentType, byte[] data) {
        String ext = extension(fileName);
        try {
            String text;
            if (TEXT_EXTENSIONS.contains(ext) || (contentType != null && contentType.startsWith("text/"))) {
                text = decode(data);
            } else if ("pdf".equals(ext)) {
                try (PDDocument pdf = Loader.loadPDF(data)) {
                    text = new PDFTextStripper().getText(pdf);
                }
            } else if (OFFICE_EXTENSIONS.contains(ext)) {
                try (POITextExtractor extractor = ExtractorFactory.createExtractor(new ByteArrayInputStream(data))) {
                    text = extractor.getText();
                }
            } else {
                return "";
            }
            text = text.replace("\0", "").strip();
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (Exception e) {
            // 암호가 걸렸거나 깨진 파일. 원문이 섞일 수 있어 메시지는 남기지 않는다
            log.warn("text extraction failed: ext={} error={}", ext, e.getClass().getSimpleName());
            return "";
        }
    }

    static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** UTF-8로 읽고, UTF-8이 아니면 한국어 윈도우 기본 인코딩(MS949)으로 읽는다 */
    static String decode(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString().replace("﻿", "");
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("MS949"));
        }
    }
}
