package com.aworko.server.work;

import com.aworko.server.global.error.BusinessException;
import com.aworko.server.global.error.ErrorCode;
import com.aworko.server.global.security.AuthPrincipal;
import com.aworko.server.organization.UserRole;
import com.aworko.server.work.dto.ArtifactResponse;
import com.aworko.server.work.dto.ChatMessageResponse;
import com.aworko.server.work.dto.CreateWorkRequest;
import com.aworko.server.work.dto.CustomerResponse;
import com.aworko.server.work.dto.FileUpload;
import com.aworko.server.work.dto.InternalReference;
import com.aworko.server.work.dto.SaveArtifactRequest;
import com.aworko.server.work.dto.SourceDocumentRequest;
import com.aworko.server.work.dto.SourceDocumentResponse;
import com.aworko.server.work.dto.SourceFile;
import com.aworko.server.work.dto.WorkDetail;
import com.aworko.server.work.dto.WorkStats;
import com.aworko.server.work.dto.WorkSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 업무 공간: 업무 목록·상세·생성, 고객 자료, 대화 기록 조회, 산출물 저장, 업무 현황.
 * 업무 테이블은 MCP 스키마 소유라 엔티티 대신 SQL로 읽고 쓴다. *_masked 칸은 DB의 mask_text(원문)로 채운다.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final Pattern REGISTRATION_KEY = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final int REGISTRATION_VALUE_MAX = 4000;
    private static final Pattern VERSION = Pattern.compile("v(\\d+)");
    private static final Set<String> DOC_TYPES = Set.of("rfp", "rfp_correction", "meeting_note", "call_note");
    static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    private static final String SCORED = "채점";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final String INSERT_WORK = "INSERT INTO work_cases (work_id, company_id, team_id, employee_id, "
            + "work_type, customer_id, title, status, created_at, registration, registration_masked) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, '" + WorkRows.STATUS_IN_PROGRESS + "', now(), ";
    private static final String SOURCE_DOC_COLUMNS = "attachment_id, doc_id, doc_type, version, grade, file_name, "
            + "content_type, size_bytes, length(content) AS length, uploaded_at FROM source_documents";
    /** 지정된 사내 자료(attached)와 AI가 참고한 사내 자료(accessed). 둘 다면 attached로 한 번만 */
    private static final String REFERENCES_SQL = """
            SELECT DISTINCT ON (r.doc_id, r.version) r.doc_id, r.version, d.title, r.source
              FROM (SELECT doc_id, version, 'attached' AS source FROM attached_references WHERE work_id = ?
                    UNION ALL
                    SELECT doc_id, version, 'accessed' FROM reference_access_logs WHERE work_id = ?) r
              LEFT JOIN documents d ON d.doc_id = r.doc_id AND d.version = r.version
             ORDER BY r.doc_id, r.version, (r.source = 'attached') DESC
            """;
    /** 평가 결과가 보이는 평가 건(확인 대기·검토 대기·확정)의 최신 초안 */
    private static final String SCORES_SQL = """
            SELECT w.work_type, e.created_at, d.items::text AS items
              FROM evaluations e
              JOIN work_cases w ON w.work_id = e.work_id
              LEFT JOIN LATERAL (SELECT items FROM evaluation_drafts
                                  WHERE evaluation_id = e.evaluation_id ORDER BY revision DESC LIMIT 1) d ON true
             WHERE e.company_id = ? AND w.employee_id = ? AND w.work_type IS NOT NULL
               AND e.status IN ('확인 대기', '검토 대기', '확정') AND e.created_at >= ?
            """;

    private final WorkRows workRows;
    private final DocumentTextExtractor extractor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<CustomerResponse> customers(AuthPrincipal me) {
        return jdbc.query("SELECT customer_id, name FROM customers WHERE company_id = ? ORDER BY name, customer_id",
                (rs, i) -> new CustomerResponse(rs.getString(1), rs.getString(2)), me.companyId());
    }

    /** mine = 내 업무, team = 담당 팀 업무(팀장만) */
    @Transactional(readOnly = true)
    public List<WorkSummary> list(AuthPrincipal me, String scope) {
        if ("team".equals(scope)) {
            if (!me.hasRole(UserRole.TEAM_LEAD) || me.teamId() == null) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "팀 업무 목록은 팀장만 볼 수 있습니다");
            }
            return workRows.byTeam(me).stream().map(WorkRows.Row::summary).toList();
        }
        return workRows.byEmployee(me).stream().map(WorkRows.Row::summary).toList();
    }

    @Transactional(readOnly = true)
    public WorkDetail detail(AuthPrincipal me, String workId) {
        WorkRows.Row row = workRows.readable(me, workId);
        boolean owner = row.ownedBy(me);
        String text = owner ? "content" : "content_masked";
        JsonNode registration = jdbc.queryForObject("SELECT " + (owner ? "registration" : "registration_masked")
                + "::text FROM work_cases WHERE work_id = ?", (rs, i) -> json(rs.getString(1)), workId);
        List<SourceDocumentResponse> documents = jdbc.query(
                "SELECT " + SOURCE_DOC_COLUMNS + " WHERE work_id = ? ORDER BY uploaded_at, attachment_id",
                this::sourceDocument, workId);
        List<InternalReference> references = jdbc.query(REFERENCES_SQL, (rs, i) -> new InternalReference(
                rs.getString("doc_id"), rs.getString("version"), rs.getString("title"), rs.getString("source")),
                workId, workId);
        ArtifactResponse artifact = jdbc.query("SELECT artifact_id, version, author, saved_at, " + text
                        + " FROM artifact_versions WHERE work_id = ? ORDER BY saved_at DESC, version DESC LIMIT 1",
                this::artifact, workId).stream().findFirst().orElse(null);
        return new WorkDetail(row.summary(), WorkTypeCatalog.artifactTitle(row.workType()), row.customerId(),
                row.createdAt(), row.completedAt(), owner, registration, documents, references, artifact);
    }

    /** 업무를 만들고 함께 올린 고객 자료(files)를 저장한다. 고객을 비우면 고객 미지정 */
    @Transactional
    public WorkDetail create(AuthPrincipal me, CreateWorkRequest request, List<FileUpload> files) {
        WorkType type = parseWorkType(request.workType());
        String customerId = blank(request.customerId()) ? null : request.customerId().strip();
        if (customerId != null) {
            Integer customers = jdbc.queryForObject("SELECT count(*) FROM customers WHERE customer_id = ? AND company_id = ?",
                    Integer.class, customerId, me.companyId());
            if (customers == null || customers == 0) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "고객을 찾을 수 없습니다");
            }
        }
        String workId = "W-" + newId();
        Object[] head = {workId, me.companyId(), me.teamId(), me.userId(), type == null ? null : type.getCode(),
                customerId, request.title().strip()};
        String registration = registrationJson(registrationOf(request));
        if (registration == null) {
            jdbc.update(INSERT_WORK + "NULL, NULL)", head);
        } else {
            jdbc.update(INSERT_WORK + "?::jsonb, (SELECT COALESCE(jsonb_object_agg(key, mask_text(value)), '{}'::jsonb) "
                    + "FROM jsonb_each_text(?::jsonb)))", concat(head, registration, registration));
        }
        for (FileUpload file : files) {
            store(me, workId, type, file, null, null);
        }
        return detail(me, workId);
    }

    /** 텍스트로 받은 고객 자료(JSON) */
    @Transactional
    public SourceDocumentResponse addSourceDocument(AuthPrincipal me, String workId, SourceDocumentRequest request) {
        return addSourceDocument(me, workId, new FileUpload(request.fileName(), "text/plain; charset=utf-8",
                request.content().getBytes(StandardCharsets.UTF_8)), request.docType(), request.grade());
    }

    /** 파일로 받은 고객 자료. 원본은 file_data에, 꺼낸 텍스트는 content에 둔다 */
    @Transactional
    public SourceDocumentResponse addSourceDocument(AuthPrincipal me, String workId, FileUpload file,
                                                    String docType, String grade) {
        WorkRows.Row row = workRows.owned(me, workId);
        return store(me, workId, row.workType(), file, docType, grade);
    }

    /** 원본 파일. 마스킹할 수 없어 담당자 본인만. 시드 자료처럼 원본이 없으면 텍스트를 .txt로 */
    @Transactional(readOnly = true)
    public SourceFile sourceFile(AuthPrincipal me, String workId, String attachmentId) {
        workRows.owned(me, workId);
        return jdbc.query("SELECT doc_id, file_name, content_type, file_data, content FROM source_documents "
                        + "WHERE work_id = ? AND attachment_id = ?", (rs, i) -> rs.getBytes("file_data") != null
                        ? new SourceFile(rs.getString("file_name"), rs.getString("content_type"), rs.getBytes("file_data"))
                        : new SourceFile(rs.getString("doc_id") + ".txt", "text/plain; charset=utf-8",
                        rs.getString("content").getBytes(StandardCharsets.UTF_8)), workId, attachmentId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자료를 찾을 수 없습니다"));
    }

    private SourceDocumentResponse store(AuthPrincipal me, String workId, WorkType workType, FileUpload file,
                                         String docTypeOrNull, String grade) {
        lockWork(workId);
        if (blank(file.fileName()) || file.data() == null || file.data().length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "빈 파일은 올릴 수 없습니다");
        }
        if (file.data().length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "파일은 20MB까지 올릴 수 있습니다");
        }
        String text = extractor.extract(file.fileName(), file.contentType(), file.data());
        String docType = blank(docTypeOrNull)
                ? (workType == WorkType.RFP_ANALYSIS ? "rfp" : "meeting_note") : docTypeOrNull.strip().toLowerCase(Locale.ROOT);
        if (!DOC_TYPES.contains(docType)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "doc_type은 rfp, rfp_correction, meeting_note, call_note 중 하나입니다");
        }
        String docId = docIdOf(file.fileName());
        String version = "v" + (maxVersion(jdbc.queryForList(
                "SELECT version FROM source_documents WHERE work_id = ? AND doc_id = ?", String.class, workId, docId)) + 1);
        String attachmentId = "ATT-" + newId();
        jdbc.update("INSERT INTO source_documents (attachment_id, work_id, doc_id, doc_type, version, grade, uploaded_by, "
                        + "uploaded_at, content, content_masked, file_name, content_type, size_bytes, file_data) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, mask_text(?), ?, ?, ?, ?)",
                attachmentId, workId, docId, docType, version, blank(grade) ? "대외비" : grade.strip(),
                me.userId(), text, text, fileNameOf(file.fileName()),
                blank(file.contentType()) ? "application/octet-stream" : file.contentType(), (long) file.data().length,
                file.data());
        return jdbc.queryForObject("SELECT " + SOURCE_DOC_COLUMNS + " WHERE attachment_id = ?", this::sourceDocument,
                attachmentId);
    }

    /** messages 뷰. 본인은 원문, 팀장은 마스킹본 */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(AuthPrincipal me, String workId) {
        WorkRows.Row row = workRows.readable(me, workId);
        String text = row.ownedBy(me) ? "content" : "content_masked";
        return jdbc.query("SELECT message_id, seq, role, " + text + " AS content, created_at FROM messages "
                        + "WHERE work_id = ? ORDER BY seq",
                (rs, i) -> new ChatMessageResponse(rs.getString("message_id"), rs.getInt("seq"), rs.getString("role"),
                        rs.getString("content"), rs.getObject("created_at", OffsetDateTime.class)), workId);
    }

    /** 새 버전(v1, v2 …)으로 저장한다. 근거 ID용 산출물 구간(artifact_spans)은 없으면 business-mcp가 만든다 */
    @Transactional
    public ArtifactResponse saveArtifact(AuthPrincipal me, String workId, SaveArtifactRequest request) {
        workRows.owned(me, workId);
        lockWork(workId);
        List<Map<String, Object>> saved = jdbc.queryForList(
                "SELECT artifact_id, version FROM artifact_versions WHERE work_id = ? ORDER BY saved_at DESC", workId);
        String artifactId = saved.isEmpty() ? "ART-" + workId.substring(2) : (String) saved.get(0).get("artifact_id");
        String version = "v" + (maxVersion(saved.stream().filter(r -> artifactId.equals(r.get("artifact_id")))
                .map(r -> (String) r.get("version")).toList()) + 1);
        jdbc.update("INSERT INTO artifact_versions (artifact_id, version, work_id, author, saved_at, content, content_masked) "
                + "VALUES (?, ?, ?, 'employee', now(), ?, mask_text(?))", artifactId, version, workId,
                request.content(), request.content());
        return jdbc.queryForObject("SELECT artifact_id, version, author, saved_at, content FROM artifact_versions "
                + "WHERE artifact_id = ? AND version = ?", this::artifact, artifactId, version);
    }

    /** 업무 현황(내 업무): 단계별 수, 업무 유형별 이번 주·지난 주 평균 점수(10점 만점) */
    @Transactional(readOnly = true)
    public WorkStats stats(AuthPrincipal me) {
        List<WorkSummary> works = list(me, "mine");
        Map<String, Integer> stages = new LinkedHashMap<>();
        WorkRows.STAGES.forEach(s -> stages.put(s, 0));
        works.forEach(w -> stages.merge(w.stage(), 1, Integer::sum));

        LocalDate thisMonday = LocalDate.now(clock.withZone(ZONE)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime thisWeek = thisMonday.atStartOfDay(ZONE).toOffsetDateTime();
        Map<String, List<Double>> current = new LinkedHashMap<>(), previous = new LinkedHashMap<>();
        jdbc.query(SCORES_SQL, rs -> {
            Double score = score10(json(rs.getString("items")));
            if (score != null) {
                boolean now = !rs.getObject("created_at", OffsetDateTime.class).isBefore(thisWeek);
                (now ? current : previous).computeIfAbsent(rs.getString("work_type"), k -> new ArrayList<>()).add(score);
            }
        }, me.companyId(), me.userId(), thisWeek.minusWeeks(1));

        List<WorkStats.TypeScore> byType = new ArrayList<>();
        for (WorkType type : WorkType.values()) {
            int count = (int) works.stream().filter(w -> type.getCode().equals(w.workType())).count();
            byType.add(new WorkStats.TypeScore(type.getCode(), type.getLabel(), count,
                    average(current.get(type.getCode())), average(previous.get(type.getCode()))));
        }
        return new WorkStats(works.size(), stages, stages.get("자료 보류"), byType);
    }

    /** 평가 리포트와 같은 규칙: 채점 항목 점수 합 / (채점 항목 수 × 2). 미관측은 빼고 10점 만점으로 */
    static Double score10(JsonNode items) {
        if (items == null || !items.isArray()) {
            return null;
        }
        int total = 0, scored = 0;
        for (JsonNode item : items) {
            if (SCORED.equals(item.path("status").asText()) && item.path("score").isNumber()) {
                total += item.path("score").asInt();
                scored++;
            }
        }
        return scored == 0 ? null : total * 10.0 / (scored * 2);
    }

    private static Double average(List<Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        return Math.round(scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow() * 10) / 10.0;
    }

    private void lockWork(String workId) {
        // 같은 업무의 동시 저장이 같은 버전 번호를 고르지 않도록
        jdbc.queryForList("SELECT 1 FROM work_cases WHERE work_id = ? FOR UPDATE", workId);
    }

    static WorkType parseWorkType(String code) {
        try {
            return WorkTypeCatalog.parse(code);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "work_type은 rfp_analysis, proposal, quote, inquiry_reply(또는 rfp, reply) 중 하나입니다");
        }
    }

    /** registration에 「목적·요구사항」(purpose) 한 칸을 합친다. 둘 다 없으면 등록 기록 없음(null) */
    static Map<String, String> registrationOf(CreateWorkRequest request) {
        if (request.registration() == null && blank(request.purpose())) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>(request.registration() == null ? Map.of() : request.registration());
        if (!blank(request.purpose())) {
            out.putIfAbsent("purpose", request.purpose().strip());
        }
        return out;
    }

    private String registrationJson(Map<String, String> registration) {
        if (registration == null) {
            return null;
        }
        for (Map.Entry<String, String> e : registration.entrySet()) {
            if (!REGISTRATION_KEY.matcher(e.getKey()).matches()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "registration 항목 이름이 올바르지 않습니다: " + e.getKey());
            }
            if (e.getValue() == null || e.getValue().length() > REGISTRATION_VALUE_MAX) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "registration 값은 " + REGISTRATION_VALUE_MAX + "자 이하 문자열입니다: " + e.getKey());
            }
        }
        try {
            return objectMapper.writeValueAsString(registration);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 파일 이름에서 경로·확장자를 뺀 것 */
    static String docIdOf(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.strip();
        return name.isEmpty() ? "DOC" : name.substring(0, Math.min(name.length(), 120));
    }

    /** 경로를 뺀 파일 이름(최대 200자) */
    static String fileNameOf(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return name.substring(0, Math.min(name.length(), 200));
    }

    static int maxVersion(Collection<String> versions) {
        int max = 0;
        for (String v : versions) {
            Matcher m = VERSION.matcher(v == null ? "" : v);
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Object[] concat(Object[] head, Object... tail) {
        Object[] out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private JsonNode json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private SourceDocumentResponse sourceDocument(ResultSet rs, int i) throws SQLException {
        int length = rs.getInt("length");
        return new SourceDocumentResponse(rs.getString("attachment_id"), rs.getString("doc_id"), rs.getString("doc_type"),
                rs.getString("version"), rs.getString("grade"), rs.getString("file_name"), rs.getString("content_type"),
                rs.getObject("size_bytes", Long.class), length, length > 0, rs.getObject("uploaded_at", OffsetDateTime.class));
    }

    private ArtifactResponse artifact(ResultSet rs, int i) throws SQLException {
        return new ArtifactResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getString(5));
    }
}
