package com.aworko.server.settings;

import com.aworko.server.settings.dto.RubricVersionChange;
import java.util.List;
import java.util.Map;

/**
 * 평가 기준 초안 쓰기(수정 초안 만들기·항목 저장·승인·폐기). 내용 검사(빈 칸·현행 내규 조항·민감정보)와
 * Postgres·Chroma 반영은 business-mcp가 한 트랜잭션에서 한다. 검색 색인이 어긋나지 않도록 백엔드가 직접 쓰지 않는다.
 * workTypes가 null이면 MCP 기본값(만들기: 4개 유형 모두, 승인·폐기: 회사의 모든 초안).
 */
public interface RubricGateway {

    List<RubricVersionChange> createDrafts(String companyId, List<String> workTypes, String createdBy);

    /** @param fields MCP 칸 이름(criterion, score_2 …)으로 보낸 칸만 바꾼다. @return 실제로 바뀐 칸 */
    List<String> updateItem(String companyId, String workType, String item, Map<String, Object> fields, String editedBy);

    List<RubricVersionChange> approve(String companyId, List<String> workTypes, String approvedBy);

    List<RubricVersionChange> discard(String companyId, List<String> workTypes);

    /** MCP 오류 응답. code는 MCP 오류 코드(VALIDATION_ERROR, NOT_FOUND, VERSION_CONFLICT, TEMPORARY_FAILURE …) */
    class Rejected extends RuntimeException {

        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
