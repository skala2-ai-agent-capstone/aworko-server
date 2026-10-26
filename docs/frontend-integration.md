# 프론트엔드 연동 (front-end `feat/react-migration` 기준)

목업(`data/tasks.js`·`useLiveFeedback`·`settingsEngine.js`)을 아래 API로 바꾸면 된다. 요청·응답 칸 정본은 Swagger(`/swagger-ui.html`)다.

## 접속

- 서버: `http://34.172.91.57:8080` (ax-eval-gpu, HTTP, 개발용). 살아 있는 Swagger는 `/swagger-ui.html`, 배포 시점 명세는 [swagger.html](swagger.html)·[openapi.json](openapi.json).
- 개발 서버: `vite.config.js`의 프록시 `target`을 위 주소로 바꾼다. 프록시를 쓰면 CORS 설정이 필요 없다.
- 응답은 `{ok, data}` / `{ok:false, error:{code, message}}`이고 칸은 snake_case다. 기존 `api/client.js`가 camelCase로 바꿔 준다.
- 로그인: `POST /api/auth/login {employee_no, password}` → `access_token`. 이후 요청에 `Authorization: Bearer …`를 붙인다(이미 연결됨).
- 회원 가입: `POST /api/auth/signup {employee_no, password(8~64자), name, role, company_name, team_name(선택)}` → 201, 응답은 로그인과 같다(바로 로그인 상태).
  - `role`: `member`(팀원, 기본값) | `team_lead`(팀장: 평가 검토·팀 대시보드·운영 설정). 가입 화면에서 고르게 한다.
  - `company_name`·`team_name`: 같은 이름(대소문자·앞뒤 공백 무시)이 있으면 그 회사·팀에 들어가고, 없으면 새로 만든다. 팀은 회사 안에서 찾는다.
  - 고를 목록은 `GET /api/auth/companies`(로그인 불필요) → `[{company_id, name, teams:[{team_id, name}]}]`. 목록의 `name`을 그대로 보내면 기존 회사·팀에 들어간다.
  - 이미 있는 사번은 409 `CONFLICT`, 잘못된 역할·짧은 비밀번호 등은 400 `VALIDATION_ERROR`.
  - 새로 만든 회사에는 평가 기준(루브릭)·고객·사내 자료가 없다. 평가·피드백 시연은 기존 회사(`LG CNS 영업담당 (가상 테넌트)`)로 하는 편이 낫다.

## 화면별

### 업무 현황 `TaskDashboardPage`

- `GET /api/works/summary`
  - `total`
  - `stages` (`{"작성 중": 5, "평가 중": 6, …}`)
  - `needs_material`: 자료 보류 수 → 상단 경고 배너
  - `by_type[]`: `{work_type, label, works, this_week, last_week}`. 평균 점수는 10점 만점이고, 평가가 없으면 칸이 빠진다
- 최근 업무 카드는 `GET /api/works`를 쓴다.

### AI 업무 공간 목록 `WorkspaceListPage`

`GET /api/works`(팀장은 `?scope=team`이면 팀 업무). 한 줄의 칸은 다음과 같다.

| 목업 칸 | API 칸 |
| --- | --- |
| `title` | `title` |
| `type` | `work_type_label` |
| `typeKey`(필터 rfp·proposal·quote·reply) | `type_key` (그대로 씀) |
| `status` | `stage` |
| `updated` | `updated_at` (ISO 시각. 「10분 전」 표시는 프론트에서 만든다) |
| 목록 키 | `work_id` |

`stage` → 배지 색:

| stage | 배지 |
| --- | --- |
| 작성 중 | `badge-gray` |
| 평가 대기 · 평가 중 | `badge-amber` |
| 확인 대기 · 검토 중 | `badge-blue` |
| 확정 | `badge-green` |
| 자료 보류 · 평가 실패 | `badge-red` |

### 새 업무 생성 `NewTaskModal`

모달 입력을 그대로 **multipart**로 보낸다. 고객 선택 칸은 필요 없다(고객 미지정으로 만들어진다).

```js
const form = new FormData();
form.append('title', title);           // 업무명 · 고객사
form.append('work_type', typeKey);     // rfp | proposal | quote | reply 그대로
form.append('purpose', purpose);       // 목적 · 요구사항
files.forEach((f) => form.append('files', f));  // File 객체(이름만이 아니라 파일 자체), 파일당 20MB
const res = await fetch('/api/works', { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
// Content-Type은 직접 넣지 않는다(브라우저가 boundary를 붙인다). 응답 data.summary.work_id로 /workspace/{id} 이동
```

- `api/client.js`는 JSON만 보내므로 multipart용 함수를 하나 추가한다. 응답 봉투 처리는 같다.
- 지금 모달은 `addFiles`에서 `f.name`만 저장한다. 파일 객체를 그대로 보관해야 한다.
- PDF·DOCX·XLSX·PPTX·텍스트(UTF-8/MS949)는 서버가 내용을 꺼내 AI 대화와 평가에 쓴다.
  - 그 밖의 형식(HWP·이미지)은 원본만 저장되고 `text_extracted: false`가 된다. 「AI가 읽지 못하는 형식」이라고 안내하면 된다.
- 업무를 만든 뒤 자료를 더 올릴 때는 `POST /api/works/{id}/source-documents`(multipart `file`)를 쓴다.

### 업무 공간 `WorkspacePage`

`GET /api/works/{id}` 응답 칸은 다음과 같다.

| 목업 칸 | API 칸 |
| --- | --- |
| `title` | `summary.title` |
| `type` | `summary.work_type_label` |
| `status` | `summary.stage` |
| `purpose` | `registration.purpose` (텍스트. HTML 아님, 줄바꿈 그대로) |
| `customerRefs` | `source_documents[].file_name` |
| `internalRefs` | `internal_references[].title` |
| `artifactTitle` | `artifact_title` |
| `artifactVersion` | `artifact.version` (없으면 「초안 작성 전」) |
| `artifactBody` | `artifact.content` (텍스트·마크다운) |

- 원본 내려받기: `GET /api/works/{id}/source-documents/{attachment_id}/file` (본인만)
- `editable`이 false면 팀장이 다른 사람 업무를 보는 경우다. 읽기만 하게 한다(입력창·저장 버튼 숨김).

대화:

- 처음 열 때 `GET /api/works/{id}/messages` → `[{seq, role, content, created_at}]`. `role`은 `user` | `assistant`이고, 목업의 `ai`가 `assistant`다.
- 보내기: `POST /api/works/{id}/messages {content}` → `{question, reply}`. 로컬 LLM이라 수십 초 걸릴 수 있다. 그동안 「···」을 보여 준다.
  - 실패하면 503이다. 질문만 기록에 남는다.
- 본문은 텍스트다. `dangerouslySetInnerHTML` 대신 텍스트로 넣거나 마크다운 렌더러를 쓴다.

산출물:

- 「초안 저장」: `POST /api/works/{id}/artifacts {content}` → 새 버전 `{artifact_id, version}` (v1, v2 …)
- 「최종 완료」: `POST /api/work-events {task_id: work_id, deliverable_version: 방금 저장한 version}` → 업무가 완료되고 평가가 시작된다(`stage`가 평가 중으로 바뀜).
  - 유형이 없으면 409다.
- 문서 영역(`doc-body`)을 편집 가능하게 만들어 그 텍스트를 저장하면 된다.

### 실시간 피드백 `useLiveFeedback` · `LiveFeedbackPanel`

```js
const { liveEvalId } = await api.post('/api/live-evaluations', { workId });
// 2~3초마다
const d = await api.get(`/api/live-evaluations/${liveEvalId}`);
setPhase(d.phase);                                     // waiting | generating | done | failed
if (d.phase === 'done') setResult(d.display);          // {items:[{label, state, note}], coaching, suggestedPrompt}
```

- `display`는 목업 `result`와 같은 모양이다. `state`는 `good` | `warn` | `pending`(`LF_STATE_META` 그대로)이다.
- 쿨다운은 서버가 정한다.
  - 5분 안에 다시 누르면 429이고 `error.retry_after_seconds`가 남은 초다(`client.js`가 `err.retryAfterSeconds`로 넘김).
  - 완료된 질문·응답 쌍이 아직 없으면 409다.
- 피드백 범위는 「최근 완료 질문·응답 최대 5쌍」이다. 패널 문구 「최근 대화 5개」는 「최근 대화 5회」로 바꾸면 된다.
- `failed`면 `error`(오류 코드)가 온다. 「피드백을 만들지 못했어요. 잠시 후 다시 요청해 주세요」로 보여 준다.

### 평가 리포트·검토 `EvaluationReportPage` · `ReviewDetailPage`

- 평가 중 → 확인 대기: Agent가 초안·코칭을 저장하면 백엔드가 완료 알림을 받아 바꾼다. 리포트 화면은 `display_status`를 폴링하면 된다.
- 이의 제기 `POST /api/evaluations/{id}/appeals` → 202, 응답 `status`가 바로 `검토 대기`다(담당 팀장에게 검토 요청이 배정됨). `target_items`는 item_id나 항목 이름, 평가에 없는 항목이면 400.
- 검토 결정 `override`는 `new_scores`가 필수다(항목별 0~2, 보안 준수는 0·2). 팀장 점수가 새 초안으로 반영되고 `확인 대기`로 돌아간다. AI 재평가는 하지 않는다. 화면 안내 「재평가 후 본인 확인 단계로 돌아가요」는 「수정한 점수로 본인 확인 단계로 돌아가요」가 맞다.

### 팀 코칭 리포트 `TeamDashboardPage` (팀장)

화면은 아직 없다. 팀 대시보드의 「리포트」 자리에 붙인다. 담당 팀장만, 그 외 403.

| 화면 동작 | API |
| --- | --- |
| 리포트 만들기 | `POST /api/teams/{team_id}/evaluations` `{from, to}`(대시보드와 같은 기간, 끝 포함) → 202 `{request_id, status:대기, release_approved, unapproved_work_types[]}` |
| 목록·상태 폴링 | `GET /api/teams/{team_id}/evaluations` → 최근 20건 `{request_id, status(대기\|완료\|실패), error, period_from, period_to, release_approved, report_status, requested_at, completed_at}`. 대기인 동안 3초마다 |
| 리포트 보기 | `GET /api/teams/{team_id}/evaluations/{request_id}` → `{evaluation, report}`. `report`(완료일 때): `status(generated\|insufficient_data\|withheld)`, `summary`, `metrics[]`, `strengths[]`, `improvements[]` |

- 수치는 **같은 기간으로 집계 승인한 경우에만** 나온다. 기간에 확정 평가가 있는 유형 중 승인 안 된 것이 있으면 `release_approved=false`, `unapproved_work_types`에 그 유형이 오고 리포트는 `withheld`(수치 없음)다. 요청 전에 「집계 승인」을 먼저 받게 안내한다.
- `실패`의 `error`는 Worker 오류 코드다. `TIMED_OUT`은 30분 넘게 처리되지 않은 것(Worker 미가동). 다시 만들 때는 새로 요청한다.
- 리포트에는 팀 집계와 근거·코칭 선택만 있다. 개인 이름·평가 건 ID는 없고, 이 API로 개인 평가 상세를 열 수 없다.

### 운영 설정 `AdminSettingsPage` (팀장)

- 사내 자료: `GET /api/settings/documents`
- 평가 기준 버전: `GET /api/settings/rubrics` → 유형별 `{doc_id, version, status(현행|초안|폐기), effective_from, note}`. `note`는 초안을 만든 곳(`평가 기준 생성 Agent` / `관리자 수정 초안(이름)`)과 승인 기록
- 버전 상세: `GET /api/settings/rubrics/{doc_id}/{version}` → `items[]`: `{item_id, item, criterion, score2, score1, score0, unobserved, needs_review, evidence_sources, related_clauses}`
- 내규 조항: `GET /api/settings/policy-clauses` → 현행 내규 조항 `{clause_id, label, text}`. `related_clauses`로 고를 수 있는 값

평가 기준 쓰기는 business-mcp 내부 API를 거쳐 저장된다(내용 검사·검색 색인 반영은 MCP 몫). 버전은 업무 유형마다 따로다.

| 화면 동작 | API |
| --- | --- |
| 수정 초안 만들기 | `POST /api/settings/rubrics/drafts` `{work_types?}` — 현행 복사. 비우면 4개 유형 모두. 이미 초안이 있으면 409 |
| 항목 저장·되돌리기 | `PATCH /api/settings/rubrics/{doc_id}/{version}/items/{item_id}` — 보낸 칸만 고친다. 초안이 아니면 409, 빈 칸·없는 조항·민감정보는 400(MCP 메시지 그대로). 되돌리기는 현행 항목 값을 보낸다 |
| 승인 후 적용 | `POST /api/settings/rubrics/approve` `{work_types?}` — 초안 → 현행, 이전 현행 → 폐기. 비우면 모든 초안 |
| 초안 폐기 | `POST /api/settings/rubrics/discard` `{work_types?}` |

검토 경고 「확인함」은 저장하지 않는다(화면에서만 기억).

내규 업로드 → 평가 기준 초안 자동 생성(「평가 기준 설계 에이전트」 카드):

| 화면 동작 | API |
| --- | --- |
| 내규 올리기 | `POST /api/settings/documents` multipart `file`(.pdf·.txt), `version`, `title?`, `effective_from?` → 201 `{document, generation}`. 새 버전이 바로 현행(이전 현행은 폐기). `document`: `{doc_id, version, chunks, clauses, diff{base, added, removed, changed}\|null, duplicate, rag_reloaded}`. `generation.status=대기`. 필요한 조항이 없으면 400(MCP 메시지), 이미 있는 버전에 다른 내용이면 409, 생성 진행 중이면 409. 같은 버전·같은 내용이면 `duplicate=true`, `generation` 없음 |
| 생성 상태 폴링 | `GET /api/settings/rubric-generations/latest` → `{generation_id, status(대기\|완료\|실패), policy_doc_id, policy_version, work_types, drafts[{work_type, doc_id, version}], error, error_message, created_at, finished_at}`. 대기인 동안 3초마다. 30분 넘게 대기면 실패(`TIMED_OUT`)로 온다. 생성한 적이 없으면 `data` 없음 |
| 다시 생성 | `POST /api/settings/rubric-generations` → 202 현행 내규로 4개 유형 초안을 다시 만든다. 진행 중이면 409, 현행 내규가 없으면 404 |

완료되면 초안이 `GET /api/settings/rubrics`에 `초안`으로 보인다(위 승인 후 적용으로 현행). Agent가 저장하면 같은 유형의 기존 초안(관리자 수정 포함)을 덮어쓴다.
생성은 Agent 기준 생성 Worker(`ai_agents.cli.rubric_worker`)가 `rubric_generation.requests`를 소비할 때만 진행된다.

## 아직 백엔드에 없는 것

| 기능 | 이유 |
| --- | --- |
| 운영 설정: 제품 설명서·가격표 업로드 | 적재 경로(MCP)가 내규만 있다. 내규 업로드는 위 API |
| 평가 기준 항목별 변경 이력 | 저장할 테이블이 없다(버전 단위 기록은 `note`·`effective_from`) |
| 대화 중 사내 자료 자동 참고(RAG) | rag-mcp를 업무 공간에서 부를 토큰 종류가 아직 없다 |
| 다른 회사로 전환 | 사용자는 회사 1곳 소속 |
