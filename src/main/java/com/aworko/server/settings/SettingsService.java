package com.aworko.server.settings;

import com.aworko.server.global.common.CodedEnum;
import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.UserRole;
import com.aworko.server.settings.dto.DocumentSummary;
import com.aworko.server.settings.dto.RubricDetail;
import com.aworko.server.settings.dto.RubricSummary;
import com.aworko.server.work.WorkType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 설정(팀장): 사내 자료와 평가 기준(루브릭) 조회. 내 회사 자료만 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String RUBRIC_SQL = """
            SELECT d.doc_id, d.version, d.work_type, d.title, d.status, d.effective_from,
                   (SELECT count(*) FROM rubric_items r WHERE r.doc_id = d.doc_id AND r.version = d.version) AS item_count
              FROM documents d
             WHERE d.company_id = ? AND d.collection = 'rubrics'
            """;

    private final JdbcTemplate jdbc;

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
        List<RubricDetail.Item> items = jdbc.query("SELECT item_id, seq, item, criterion, score_2, score_1, score_0, "
                        + "unobserved, needs_review, evidence_sources, related_clauses FROM rubric_items "
                        + "WHERE doc_id = ? AND version = ? ORDER BY seq",
                (rs, i) -> new RubricDetail.Item(rs.getString("item_id"), rs.getInt("seq"), rs.getString("item"),
                        rs.getString("criterion"), rs.getString("score_2"), rs.getString("score_1"),
                        rs.getString("score_0"), rs.getString("unobserved"), rs.getString("needs_review"),
                        texts(rs.getArray("evidence_sources")), texts(rs.getArray("related_clauses"))),
                docId, version);
        return new RubricDetail(rubric, items);
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
                rs.getObject("effective_from", LocalDate.class), rs.getInt("item_count"));
    }

    private static List<String> texts(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static void requireTeamLead(AuthPrincipal me) {
        if (!me.hasRole(UserRole.TEAM_LEAD)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "운영 설정은 팀장만 볼 수 있습니다");
        }
    }
}
