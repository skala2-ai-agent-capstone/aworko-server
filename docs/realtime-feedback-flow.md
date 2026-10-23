# 실시간 프롬프팅 피드백 흐름 (서비스 간 계약)

> 대상: 채팅(로컬 LLM) · AI Agent · MCP · 백엔드(Spring) · 프론트엔드 담당자
> 상태: 2026-10-23 개정 — **버튼 요청 + 폴링** 방식. 요청 행은 business-mcp 내부 API가 만든다(범위·대화 스냅샷 고정). **[미정]** 표시는 팀 간 합의가 필요한 항목이다.
> DB 구조 기준: [db-schema.json](db-schema.json)

## 0. 한 줄 요약
사용자가 **[실시간 피드백] 버튼**을 누르면 백엔드가 business-mcp `POST /internal/live-evaluations`로 요청 행(최근 완료 질문·응답 최대 5쌍, 마스킹된 대화 스냅샷)을 만들고 RabbitMQ에 **ID만** 발행한다.
Agent는 MCP로 대화를 읽어 피드백을 만들고 MCP로 같은 행에 결과를 저장한다. 프론트는 **폴링**으로 결과를 받는다.

대화 저장은 이 흐름과 별개다. 로컬 LLM과의 대화는 기록 프록시가 `llm_exchanges`에 바로 저장하고, `messages` 뷰로 펼쳐 읽는다.

## 1. 구성 요소와 역할

| 구성 요소 | 하는 일 | 하지 않는 일 |
|---|---|---|
| 채팅 서버 / 기록 프록시 (로컬 LLM) | 사용자와 LLM 대화, `llm_exchanges`에 대화 저장 | 피드백 요청·발행 |
| 프론트엔드 | 버튼 → 요청, 2~3초 간격 폴링, 결과·실패·대기 시간 표시 | RabbitMQ·DB 직접 접근 |
| 백엔드 (Spring) | 인증·권한, 5분 1회 제한, business-mcp 내부 API로 요청 행(대기) 생성 + 아웃박스 발행, 상태 조회 API, DLQ 수신 시 `실패` 기록 | 피드백 생성, LLM 호출, 범위·스냅샷 직접 계산, 큐에 대화 원문 넣기 |
| RabbitMQ | 피드백 요청 전달, 재시도·DLQ | 업무 규칙 판단 |
| AI Agent (Worker) | 메시지 소비 → MCP로 대화 읽기 → LLM 피드백 → MCP로 결과 저장 | DB 직접 접근, 백엔드 호출, 마스킹 전 원문을 LLM에 전달 |
| business-mcp | 요청 행 생성(내부 API: 최근 완료 5쌍 범위·유형·현행 기준·대화 스냅샷 고정), 대화 조회 도구, 피드백 결과 저장 도구(중복 방지) | 권한 우회, 회사 범위 없는 조회 |

## 2. 처리 흐름

```
[브라우저] ─① POST /api/live-evaluations {work_id}─▶ [Spring]
                                                      │ ② 권한·5분 제한 확인
                                                      │ ③ business-mcp POST /internal/live-evaluations → live_evaluations(대기), 이어서 outbox
[브라우저] ◀─ 202 {live_eval_id} ─────────────────────┤
                                                      └─④ outbox 릴레이 → RabbitMQ live_evaluation.requests {live_eval_id, …}
                                                                                     │
[AI Agent] ◀─⑤ 소비──────────────────────────────────────────────────────────────────┘
     ├─⑥ MCP start_live_evaluation(live_eval_id)          → status = 생성 중
     ├─⑦ MCP get_messages(work_id, from_seq, to_seq)      → 요청 때 고정한 마스킹 대화(최대 5쌍)
     ├─⑧ LLM 피드백 생성
     └─⑨ MCP save_live_evaluation(live_eval_id, items, coaching) → status = 완료  (같은 키 재저장은 기존 결과 반환)

[브라우저] ─⑩ GET /api/live-evaluations/{id} (2~3초마다)─▶ [Spring] ─▶ DB 조회
           ◀─ {status: 대기|생성 중|완료|실패, items, coaching} ── 완료·실패면 폴링 중단
```

| 단계 | 구간 | 방식 | 내용 |
|---|---|---|---|
| ① | 브라우저 → Spring | HTTPS + JWT | 피드백 요청. 대상 업무(`work_id`) 지정 |
| ② | Spring → DB | JDBC | 업무 소유자 확인, 사용자의 마지막 요청 시각 확인(5분) |
| ③ | Spring → business-mcp → DB | HTTP(`X-Internal-Key`) | MCP가 `conversation_pairs`에서 요청 시각까지 성공한 최근 완료 질문·응답 최대 5쌍을 골라 `live_evaluations`(대기, from_seq·to_seq·유형·현행 기준·대화 스냅샷)를 커밋. 백엔드는 응답의 범위로 `outbox_events`를 기록 |
| ④ | Spring → RabbitMQ | AMQP | 아웃박스 릴레이가 발행 (ID만) |
| ⑤ | RabbitMQ → Agent | AMQP | Worker가 소비 |
| ⑥ | Agent → MCP → DB | MCP | 상태를 `생성 중`으로 변경 (대기일 때만) |
| ⑦ | Agent → MCP → DB | MCP | 고정된 범위의 대화 조회(마스킹본) |
| ⑧ | Agent → LLM | HTTPS | 마스킹된 대화로 프롬프팅 피드백 생성 |
| ⑨ | Agent → MCP → DB | MCP | 결과 저장, `완료`로 변경 → ack |
| ⑩ | 브라우저 → Spring | HTTPS + JWT | 상태·결과 폴링 |

**버튼을 누른 순간의 대화 범위(from_seq~to_seq)를 고정한다.** 그 사이 대화가 더 진행돼도 Agent는 요청 때 고정한 스냅샷(최대 5쌍)으로 피드백한다.

## 3. 백엔드 API

| 메서드 | 경로 | 요청 | 응답 | 권한 |
|---|---|---|---|---|
| POST | `/api/live-evaluations` | `{"work_id": "W-…"}` | **202** `{"live_eval_id", "status": "대기", "from_seq", "to_seq"}` | 업무 담당자 본인 |
| GET | `/api/live-evaluations/{id}` | — | 200 `{"live_eval_id", "status", "items", "coaching", "error", "requested_at"}` | 요청자 본인 |

오류 응답:

| 상황 | 코드 | 비고 |
|---|---|---|
| 5분 안에 다시 요청 | **429** | `retry_after_seconds` 포함. 프론트는 "N분 후 다시 요청할 수 있어요" 표시 |
| 완료된 질문·응답 쌍이 없음 | 409 | MCP `VALIDATION_ERROR` |
| business-mcp 연결 실패·내부 키 오류 | 503 | |
| 남의 업무·다른 회사 | 403 | |
| 없는 요청 ID | 404 | |

- 5분 제한은 `live_evaluations.requested_at` 기준, **사용자별**이다(업무가 달라도 5분에 1회).
- 완료 쌍이 5개보다 적으면 있는 만큼(1~4쌍)만 범위로 잡는다. 답이 없는 마지막 질문은 넣지 않는다.

### 프론트 폴링 규칙
- 202를 받으면 `GET /api/live-evaluations/{id}`를 **2~3초 간격**으로 호출한다.
- `완료` 또는 `실패`면 중단한다. **[미정]** 60초가 지나도 끝나지 않으면 중단하고 "잠시 후 다시 확인" 안내.
- 새로고침해도 같은 ID로 다시 조회하면 된다(정본은 DB).

## 4. RabbitMQ 계약

평가 큐(`evaluation.*`)와 분리한다. 실시간 피드백이 오래 걸리는 평가 작업 뒤에 줄 서지 않게 하기 위해서다.

| 이름 | 종류 | 설정 | 발행 | 소비 |
|---|---|---|---|---|
| `live_evaluation.exchange` | direct | | | |
| `live_evaluation.requests` | queue | routing key `live_evaluate` | 백엔드(아웃박스) | Agent |
| `live_evaluation.retry` | queue | `x-message-ttl` 5000ms, DLX → `live_evaluation.exchange` / `live_evaluate` | Agent(일시 실패 시) | 없음(지연 후 복귀) |
| `live_evaluation.dlq` | queue | | Agent(attempt 한도 초과) | 백엔드 → `실패` 기록 |

- 큐·exchange 선언은 백엔드가 기동 시 수행한다. 다른 서비스가 같은 이름·인자로 선언해도 무방하다.
- 메시지는 persistent, JSON(UTF-8, snake_case).

### 메시지 형식
```json
{
  "message_id": "6f1c…",            // 큐 메시지 ID(UUID)
  "type": "LIVE_EVALUATE",
  "live_eval_id": "LIVE-…",         // 중복 방지 키
  "company_id": "C-LGCNS-SALES",
  "work_id": "W-…",
  "requested_by": "E-1021",
  "from_seq": 3,
  "to_seq": 7,
  "rubric_version": "v2",          // [미정] 업무 유형이 정해지지 않은 업무면 null
  "attempt": 0
}
```
**대화 본문·개인정보는 넣지 않는다.** Agent는 반드시 MCP로 마스킹본을 읽는다.

### Agent 처리 규칙
- **중복 방지**: `live_eval_id`로 판단한다. 이미 `완료`·`실패`면 아무것도 하지 않고 ack.
- **재시도**: MCP·LLM 호출 실패는 Harness가 호출 단위로 재시도 → 소진 시 `attempt+1`로 `live_evaluation.retry` 발행 후 원본 ack → 한도(**[미정]** 예: 3) 초과 시 `live_evaluation.dlq`.
- **실패 기록**: DLQ로 보낼 때 `save_live_evaluation`에 실패 결과(status=실패, error)를 저장하거나, 백엔드 DLQ 수신기가 `실패`로 기록한다. 둘 중 먼저 오는 쪽이 반영되며 결과는 같다.

## 5. DB (`live_evaluations`)

| 칸 | 쓰는 쪽 | 내용 |
|---|---|---|
| `live_eval_id` | business-mcp | `LIVE-…`. 큐 중복 방지 키 |
| `work_id`, `requested_by`, `requested_at` | business-mcp | 요청 정보. `requested_at`으로 5분 제한 판단 |
| `from_seq`, `to_seq` | business-mcp | 최근 완료 질문·응답 최대 5쌍의 첫 질문~마지막 답변 `seq` |
| `work_type`, `rubric_doc_id`, `rubric_version` | business-mcp | 업무 유형과 현행 루브릭(없으면 NULL) |
| `conversation_snapshot` | business-mcp | 요청 때 고정한 마스킹 대화·exchange ID·마스킹 버전. `get_messages`가 이것을 읽는다 |
| `status` | 백엔드(대기·실패) / Agent(생성 중·완료·실패) | `대기` → `생성 중` → `완료` 또는 `실패` |
| `items`, `coaching` | Agent | 피드백 결과(jsonb). **[미정] 구조** |
| `model`, `latency_ms`, `error` | Agent | 실행 정보 |

### business-mcp 도구 (추가 필요)
| 도구 | 입력 | 처리 |
|---|---|---|
| `get_messages` | work_id, from_seq, to_seq | 해당 범위의 마스킹된 대화 |
| `start_live_evaluation` | live_eval_id | `대기`일 때만 `생성 중`으로 변경 |
| `save_live_evaluation` | live_eval_id, status(완료·실패), items, coaching, model, latency_ms, error | 결과 저장. 이미 완료·실패면 기존 결과 반환(`duplicate:true`) |

## 6. 권한·개인정보 원칙
- 실시간 피드백은 **요청자 본인만** 볼 수 있다. 팀장에게도 공개하지 않는다(기획안: 개인 피드백과 조직 집계 분리).
- 모든 조회에 `company_id` 범위를 적용한다.
- 큐 메시지, 실패 큐, 로그에는 대화 원문을 남기지 않는다.
- LLM에는 마스킹된 대화만 보낸다. **[미정] 마스킹 위치**: 기록 프록시가 저장할 때 / business-mcp가 조회할 때.

## 7. 기존 평가 흐름과의 관계
- 업무 완료 → 개인 평가·코칭 흐름(`evaluation.*` 큐, 202 + 진행 상태)은 그대로 유지한다.
- 팀 평가는 Agent가 생성한다. 백엔드는 결과 조회와 팀장 집계 승인만 맡는다.
- **[미정]** 실시간 피드백을 받고 프롬프트를 고친 행동을 최종 평가의 근거로 쓸지.

## 8. 합의가 필요한 항목
| # | 항목 | 담당 |
|---|---|---|
| 1 | `live_evaluations`·`llm_exchanges`·`messages` 뷰를 MCP 스키마에 반영 | MCP |
| 2 | `get_messages` · `start_live_evaluation` · `save_live_evaluation` 도구 추가 | MCP |
| 3 | Agent 실시간 피드백 Worker(재시도 한도, 실패 기록) | Agent |
| 4 | 피드백 결과 `items`·`coaching` JSON 구조 | Agent · 프론트 |
| 5 | 마스킹 위치(프록시 저장 시 vs MCP 조회 시) | 채팅 · MCP |
| 6 | 업무 유형 미정 업무의 루브릭 처리 | Agent · 기획 |
| 7 | 폴링 최대 대기 시간 | 프론트 · 백엔드 |
| 8 | 실시간 피드백의 평가 근거 사용 여부 | 기획 |
