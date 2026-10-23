package com.aworko.server.work.dto;

/** 내려받을 고객 자료 원본 */
public record SourceFile(String fileName, String contentType, byte[] data) {
}
