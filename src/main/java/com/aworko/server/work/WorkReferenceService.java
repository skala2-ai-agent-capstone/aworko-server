package com.aworko.server.work;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.work.dto.InternalReference;
import com.aworko.server.work.dto.ReferenceCandidate;
import com.aworko.server.work.dto.ReferenceDocument;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무에 지정하는 사내 자료(attached_references)와 담당자의 사내 자료 열람 기록(reference_access_logs).
 * 둘 다 업무 완료 때 business-mcp가 평가 스냅샷(reference_refs·열람 기록)으로 고정하므로,
 * 완료 뒤에 바꿔도 이미 만든 평가 건에는 영향이 없다.
 * 고를 수 있는 자료는 내 회사의 제품·가격 자료와 내규 중 색인 대상(기밀 제외)이다.
 */
@Service
@RequiredArgsConstructor
public class WorkReferenceService {

    private static final String CANDIDATES = "SELECT doc_id, version, title, collection, status, effective_from "
            + "FROM documents WHERE company_id = ? AND indexed AND collection IN ('references', 'policies')";

    private final WorkRows workRows;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<ReferenceCandidate> candidates(AuthPrincipal me) {
        return jdbc.query(CANDIDATES + " ORDER BY collection DESC, doc_id, effective_from DESC NULLS LAST, version",
                (rs, i) -> new ReferenceCandidate(rs.getString("doc_id"), rs.getString("version"), rs.getString("title"),
                        rs.getString("collection"), rs.getString("status"), rs.getObject("effective_from", LocalDate.class)),
                me.companyId());
    }

    /** 이미 지정한 자료면 그대로 돌려준다 */
    @Transactional
    public InternalReference attach(AuthPrincipal me, String workId, String docId, String version) {
        workRows.owned(me, workId);
        ReferenceCandidate doc = candidate(me, docId.strip(), version.strip());
        lockWork(workId);
        jdbc.update("INSERT INTO attached_references (work_id, doc_id, version, seq) "
                        + "SELECT ?, ?, ?, COALESCE(max(seq), 0) + 1 FROM attached_references WHERE work_id = ? "
                        + "ON CONFLICT DO NOTHING",
                workId, doc.docId(), doc.version(), workId);
        return new InternalReference(doc.docId(), doc.version(), doc.title(), "attached");
    }

    @Transactional
    public void detach(AuthPrincipal me, String workId, String docId, String version) {
        workRows.owned(me, workId);
        int deleted = jdbc.update("DELETE FROM attached_references WHERE work_id = ? AND doc_id = ? AND version = ?",
                workId, docId, version);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "이 업무에 지정된 자료가 아닙니다");
        }
    }

    /** 자료 본문. 담당자 본인이 열면 열람 기록을 남긴다(같은 팀 팀장의 조회는 남기지 않는다) */
    @Transactional
    public ReferenceDocument read(AuthPrincipal me, String workId, String docId, String version) {
        WorkRows.Row row = workRows.readable(me, workId);
        ReferenceCandidate doc = candidate(me, docId, version);
        String content = jdbc.queryForObject("SELECT COALESCE(content, '') FROM documents "
                + "WHERE company_id = ? AND doc_id = ? AND version = ?", String.class, me.companyId(), docId, version);
        if (row.ownedBy(me)) {
            lockWork(workId);
            jdbc.update("INSERT INTO reference_access_logs (work_id, seq, user_id, doc_id, version, accessed_at) "
                            + "SELECT ?, COALESCE(max(seq), 0) + 1, ?, ?, ?, now() FROM reference_access_logs WHERE work_id = ?",
                    workId, me.userId(), docId, version, workId);
        }
        return new ReferenceDocument(doc.docId(), doc.version(), doc.title(), doc.collection(), doc.status(),
                doc.effectiveFrom(), content);
    }

    private ReferenceCandidate candidate(AuthPrincipal me, String docId, String version) {
        return jdbc.query(CANDIDATES + " AND doc_id = ? AND version = ?",
                        (rs, i) -> new ReferenceCandidate(rs.getString("doc_id"), rs.getString("version"),
                                rs.getString("title"), rs.getString("collection"), rs.getString("status"),
                                rs.getObject("effective_from", LocalDate.class)),
                        me.companyId(), docId, version)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "지정할 수 있는 사내 자료가 아닙니다"));
    }

    private void lockWork(String workId) {
        // 같은 업무의 동시 기록이 같은 seq를 고르지 않도록
        jdbc.queryForList("SELECT 1 FROM work_cases WHERE work_id = ? FOR UPDATE", workId);
    }
}
