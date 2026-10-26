package com.aworko.server.settings;

import com.aworko.server.settings.dto.PolicyDocumentSaved;
import java.time.LocalDate;

/**
 * 내규 적재(business-mcp /internal/documents). 조·항 분할, Postgres·Chroma 반영, 이전 현행 폐기, rag-mcp 갱신을
 * MCP가 한 번에 한다. 평가 기준 생성에 필요한 조항이 없으면 저장하지 않는다(VALIDATION_ERROR).
 * 오류 응답은 평가 기준 쓰기와 같은 봉투라 RubricGateway.Rejected로 던진다.
 */
public interface PolicyDocumentGateway {

    PolicyDocumentSaved upload(PolicyUpload upload);

    /** title·effectiveFrom이 null이면 MCP 기본값(현행 내규 제목, 오늘) */
    record PolicyUpload(String companyId, String version, String sourceFile, byte[] content, String title,
                        LocalDate effectiveFrom, String uploadedBy) {
    }
}
