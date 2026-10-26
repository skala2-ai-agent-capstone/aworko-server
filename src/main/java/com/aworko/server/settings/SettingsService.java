package com.aworko.server.settings;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.UserRole;
import com.aworko.server.settings.dto.DocumentSummary;
import com.aworko.server.settings.dto.PolicyClause;
import com.aworko.server.settings.dto.RubricDetail;
import com.aworko.server.settings.dto.RubricItemSaved;
import com.aworko.server.settings.dto.RubricItemUpdate;
import com.aworko.server.settings.dto.RubricSummary;
import com.aworko.server.settings.dto.RubricTargets;
import com.aworko.server.settings.dto.RubricVersionChange;
import com.aworko.server.work.WorkType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 설정(팀장): 사내 자료와 평가 기준(루브릭). 내 회사 자료만.
 * 기준 쓰기(초안 만들기·항목 저장·승인·폐기)는 business-mcp가 한다(RubricGateway). MCP를 부르는 동안 DB 트랜잭션을 잡지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String RUBRIC_SQL = """
            SELECT d.doc_id, d.version, d.work_type, d.title, d.status, d.effective_from, d.note,
                   (SELECT count(*) FROM rubric_items r WHERE r.doc_id = d.doc_id AND r.version = d.version) AS item_count
              FROM documents d
             WHERE d.company_id = ? AND d.collection = 'rubrics'
            """;
    private static final String ITEM_SQL = "SELECT item_id, seq, item, criterion, score_2, score_1, score_0, "
            + "unobserved, needs_review, evidence_sources, related_clauses FROM rubric_items WHERE doc_id = ? AND version = ?";
    private static final String DRAFT = "초안";

    private final JdbcTemplate jdbc;
    private final RubricGateway rubricGateway;

    @Transactional(readOnly = true)
    public List<DocumentSummary> documents(AuthPrincipal me) {
        requireTeamLead(me);
        return jdbc.query("SELECT doc_id, version, title, collection, grade, status, effective_from, source_file, indexed "
                        + "FROM documents WHERE company_id = ? AND (collection IN ('references', 'policies') OR collection IS NULL) "
                        + "ORDER BY collection NULLS LAST, doc_id, version",
                (rs, i) -> new DocumentSummary(rs.getString("doc_id"), rs.getString("version"), rs.getString("title"),
                        rs.getString("collection"), rs.getString("grade"), rs.getString("status"),
                        rs.getObject("effective_from", LocalDate.class), rs.getString("source_file"),
                        rs.getBoolean("indexed")), me.companyId());
    }

    @Transactional(readOnly = true)
    public List<RubricSummary> rubrics(AuthPrincipal me) {
        requireTeamLead(me);
        return jdbc.query(RUBRIC_SQL + " ORDER BY d.work_type, d.effective_from DESC NULLS LAST, d.version DESC",
                this::rubric, me.companyId());
    }

    @Transactional(readOnly = true)
    public RubricDetail rubric(AuthPrincipal me, String docId, String version) {
        requireTeamLead(me);
        RubricSummary rubric = jdbc.query(RUBRIC_SQL + " AND d.doc_id = ? AND d.version = ?", this::rubric,
                        me.companyId(), docId, version).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "평가 기준을 찾을 수 없습니다"));
        return new RubricDetail(rubric, jdbc.query(ITEM_SQL + " ORDER BY seq", this::item, docId, version));
    }

    /** 평가 기준의 related_clauses로 고를 수 있는 현행 내규 조항. 조항마다 첫 단락만 */
    @Transactional(readOnly = true)
    public List<PolicyClause> policyClauses(AuthPrincipal me) {
        requireTeamLead(me);
        return jdbc.query("""
                        SELECT * FROM (
                            SELECT DISTINCT ON (c.clause_id) d.doc_id, d.version, c.clause_id, c.seq,
                                   regexp_replace(c.label, ' 제[0-9]+항$', '') AS label, c.text
                              FROM document_chunks c JOIN documents d ON d.doc_id = c.doc_id AND d.version = c.version
                             WHERE d.company_id = ? AND d.collection = 'policies' AND d.status = '현행'
                               AND c.clause_id IS NOT NULL
                             ORDER BY c.clause_id, c.seq) t
                         ORDER BY doc_id, seq
                        """,
                (rs, i) -> new PolicyClause(rs.getString("doc_id"), rs.getString("version"), rs.getString("clause_id"),
                        rs.getString("label"), rs.getString("text")), me.companyId());
    }

    /** 현행 기준을 복사한 수정 초안. 요청한 유형에 초안이 이미 있으면 409 */
    public List<RubricVersionChange> createDrafts(AuthPrincipal me, RubricTargets request) {
        requireTeamLead(me);
        List<String> workTypes = workTypes(request);
        return call(() -> rubricGateway.createDrafts(me.companyId(), workTypes, nameOf(me)));
    }

    /** 초안 항목 하나의 칸 일부를 고친다. 현행·폐기 버전은 고칠 수 없다(409) */
    public RubricItemSaved updateItem(AuthPrincipal me, String docId, String version, String itemId,
                                      RubricItemUpdate request) {
        requireTeamLead(me);
        Map<String, Object> fields = request.fields();
        if (fields.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "고칠 칸을 하나 이상 보내세요");
        }
        Map<String, Object> target = jdbc.queryForList("SELECT d.status, d.work_type, i.item FROM documents d "
                        + "JOIN rubric_items i ON i.doc_id = d.doc_id AND i.version = d.version "
                        + "WHERE d.company_id = ? AND d.collection = 'rubrics' AND d.doc_id = ? AND d.version = ? AND i.item_id = ?",
                        me.companyId(), docId, version, itemId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "평가 기준 항목을 찾을 수 없습니다"));
        if (!DRAFT.equals(target.get("status"))) {
            throw new BusinessException(ErrorCode.CONFLICT, "초안만 고칠 수 있습니다. 수정 초안을 먼저 만드세요");
        }
        List<String> changed = call(() -> rubricGateway.updateItem(me.companyId(), (String) target.get("work_type"),
                (String) target.get("item"), fields, nameOf(me)));
        RubricDetail.Item saved = jdbc.query(ITEM_SQL + " AND item_id = ?", this::item, docId, version, itemId).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "평가 기준 항목을 찾을 수 없습니다"));
        return new RubricItemSaved(saved, changed);
    }

    /** 초안을 현행으로, 같은 기준의 이전 현행을 폐기로. 다음 평가부터 적용된다 */
    public List<RubricVersionChange> approve(AuthPrincipal me, RubricTargets request) {
        requireTeamLead(me);
        List<String> workTypes = workTypes(request);
        return call(() -> rubricGateway.approve(me.companyId(), workTypes, nameOf(me)));
    }

    public List<RubricVersionChange> discard(AuthPrincipal me, RubricTargets request) {
        requireTeamLead(me);
        List<String> workTypes = workTypes(request);
        return call(() -> rubricGateway.discard(me.companyId(), workTypes));
    }

    private RubricSummary rubric(ResultSet rs, int i) throws SQLException {
        String type = rs.getString("work_type");
        WorkType workType = null;
        try {
            workType = type == null ? null : CodedEnum.fromCode(WorkType.class, type);
        } catch (IllegalArgumentException e) {
            // 알 수 없는 유형 코드는 이름 없이 그대로 보여 준다
        }
        return new RubricSummary(rs.getString("doc_id"), rs.getString("version"), type,
                workType == null ? null : workType.getLabel(), rs.getString("title"), rs.getString("status"),
                rs.getObject("effective_from", LocalDate.class), rs.getInt("item_count"), rs.getString("note"));
    }

    private RubricDetail.Item item(ResultSet rs, int i) throws SQLException {
        return new RubricDetail.Item(rs.getString("item_id"), rs.getInt("seq"), rs.getString("item"),
                rs.getString("criterion"), rs.getString("score_2"), rs.getString("score_1"),
                rs.getString("score_0"), rs.getString("unobserved"), rs.getString("needs_review"),
                texts(rs.getArray("evidence_sources")), texts(rs.getArray("related_clauses")));
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<String> workTypes(RubricTargets request) {
        if (request == null || request.workTypes() == null) {
            return null;
        }
        for (String code : request.workTypes()) {
            if (Arrays.stream(WorkType.values()).noneMatch(t -> t.getCode().equals(code))) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "work_types는 rfp_analysis, proposal, quote, inquiry_reply 중에서 고릅니다");
            }
        }
        return request.workTypes();
    }

    /** 초안·승인 기록(documents.note)에 남길 이름 */
    private String nameOf(AuthPrincipal me) {
        return jdbc.queryForList("SELECT name FROM users WHERE user_id = ?", String.class, me.userId()).stream()
                .findFirst().orElse(me.userId());
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RubricGateway.Rejected e) {
            throw translate(e);
        }
    }

    /** 권한은 위에서 이미 확인했으므로 MCP의 FORBIDDEN은 내부 키 설정 오류다 */
    static BusinessException translate(RubricGateway.Rejected e) {
        return switch (e.code()) {
            case "VALIDATION_ERROR" -> new BusinessException(ErrorCode.VALIDATION_ERROR, e.getMessage());
            case "NOT_FOUND" -> new BusinessException(ErrorCode.NOT_FOUND, e.getMessage());
            case "VERSION_CONFLICT" -> new BusinessException(ErrorCode.CONFLICT, e.getMessage());
            case "TEMPORARY_FAILURE" -> new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, e.getMessage());
            default -> {
                log.error("business-mcp rejected rubric request: code={}", e.code());
                yield new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "평가 기준을 저장하지 못했습니다");
            }
        };
    }

    static void requireTeamLead(AuthPrincipal me) {
        if (!me.hasRole(UserRole.TEAM_LEAD)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "운영 설정은 팀장만 볼 수 있습니다");
        }
    }
}
