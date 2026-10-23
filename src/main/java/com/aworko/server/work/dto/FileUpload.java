package com.aworko.server.work.dto;

/** 올린 파일 한 개(multipart에서 꺼낸 것) */
public record FileUpload(String fileName, String contentType, byte[] data) {
}
