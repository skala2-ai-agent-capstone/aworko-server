# 프론트엔드 연동 (front-end `feat/react-migration` 기준)

목업(`data/tasks.js`·`useLiveFeedback`·`settingsEngine.js`)을 아래 API로 바꾸면 된다. 요청·응답 칸 정본은 Swagger(`/swagger-ui.html`)다.

## 접속

- 서버: `http://34.123.201.35:8080` (ax-eval-gpu, HTTP, 개발용). 살아 있는 Swagger는 `/swagger-ui.html`, 배포 시점 명세는 [swagger.html](swagger.html)·[openapi.json](openapi.json).
- 개발 서버: `vite.config.js`의 프록시 `target`을 위 주소로 바꾼다. 프록시를 쓰면 CORS 설정이 필요 없다.
- 응답은 `{ok, data}` / `{ok:false, error:{code, message}}`이고 칸은 snake_case다. 기존 `api/client.js`가 camelCase로 바꿔 준다.
- 로그인: `POST /api/auth/login {employee_no, password}` → `access_token`. 이후 요청에 `Authorization: Bearer …`를 붙인다(이미 연결됨).

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

### 운영 설정 `AdminSettingsPage` (팀장)

조회 API만 있다.

- 사내 자료: `GET /api/settings/documents`
- 평가 기준 버전: `GET /api/settings/rubrics`
- 버전 상세: `GET /api/settings/rubrics/{doc_id}/{version}` → `items[]`: `{item, criterion, score2, score1, score0, unobserved, needs_review, evidence_sources}`

업로드 → 자동 기준 생성 → 초안 수정 → 승인은 아직 없다(아래 참고).

## 아직 백엔드에 없는 것

| 기능 | 이유 |
| --- | --- |
| 운영 설정: 자료 업로드 → 평가 기준 자동 생성·수정·승인 | 평가 기준 생성 Agent를 돌릴 실행 경로가 아직 없다 |
| 평가 기준 변경 이력 | 저장할 테이블이 없다 |
| 대화 중 사내 자료 자동 참고(RAG) | rag-mcp를 업무 공간에서 부를 토큰 종류가 아직 없다 |
| 다른 회사로 전환 | 사용자는 회사 1곳 소속 |
