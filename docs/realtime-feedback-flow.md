# 실시간 프롬프팅 피드백 흐름 (서비스 간 계약)

> 대상: 채팅 서버 · AI Agent · MCP · 백엔드(Spring) 담당자
> 상태: 초안 (2026-10-22). **[미정]** 표시는 팀 간 합의가 필요한 항목이다.

## 0. 한 줄 요약
**대화는 채팅 서버가 DB에 먼저 저장하고, RabbitMQ에는 "새 메시지 있음" 알림(ID만)을 보낸다.**
Agent는 알림을 받아 MCP로 대화를 읽고 피드백을 만들어 MCP로 저장한다. Spring은 저장된 피드백을 감지해 SSE로 사용자에게 보낸다.

## 1. 구성 요소와 역할

| 구성 요소 | 하는 일 | 하지 않는 일 |
|---|---|---|
| 채팅 서버 (로컬 LLM, Python, 별도 서버) | 사용자와 LLM 대화 처리, **대화를 DB에 저장**, 저장과 같은 트랜잭션에 아웃박스 기록 → `feedback.requests` 발행 | 피드백 생성, 큐 메시지에 대화 원문 넣기 |
| RabbitMQ | 피드백 요청 전달, 재시도·DLQ | 업무 규칙 판단 |
| AI Agent (Worker) | 메시지 소비 → MCP로 대화 읽기(마스킹본) → LLM 피드백 생성 → MCP로 피드백 저장 | DB 직접 접근, 백엔드 호출, 마스킹 전 원문을 LLM에 전달 |
| business-mcp | 대화 조회 도구, 피드백 저장 도구 제공(중복 방지 키 적용) | 권한 우회, 회사 범위 없는 조회 |
| PostgreSQL | 대화·피드백 저장(기준 데이터). 피드백 INSERT 시 `NOTIFY` | 외부 공개 |
| 백엔드 (Spring) | 인증·권한, 피드백 `LISTEN` → **SSE로 대화 당사자에게 push**, 재접속 시 놓친 피드백 재전송 | 피드백 생성·저장, LLM·MCP 호출 |
| 프론트엔드 | 채팅 화면, Spring SSE 구독, 피드백 표시 | RabbitMQ·DB 직접 접근 |

## 2. 처리 흐름

```
[브라우저] ─① 메시지 전송─▶ [채팅 서버] ─② 대화 저장 + 아웃박스 (1 트랜잭션)─▶ [PostgreSQL]
                                  │
                                  └─③ 아웃박스 릴레이 → RabbitMQ feedback.requests {conversation_id, message_id, …}
                                                                │
                                         ④ 소비 ◀──────────────┘
[AI Agent] ─⑤ MCP get_conversation(마스킹본)─▶ [business-mcp] ─▶ [PostgreSQL]
     │
     ├─⑥ LLM 피드백 생성 (마스킹된 대화만 전달)
     │
     └─⑦ MCP save_prompt_feedback(idempotency_key = message_id)─▶ [business-mcp] ─▶ [PostgreSQL]
                                                                                    │
                                                                ⑧ INSERT 트리거 NOTIFY
                                                                                    ▼
[브라우저] ◀─⑩ SSE event: feedback ── [Spring] ◀─⑨ LISTEN prompt_feedback ─────────┘
```

| 단계 | 구간 | 방식 | 내용 |
|---|---|---|---|
| ① | 브라우저 → 채팅 서버 | HTTPS | 사용자 메시지 전송 |
| ② | 채팅 서버 → DB | SQL | 사용자 메시지(+LLM 응답) 저장. **같은 트랜잭션에 아웃박스 행 기록** |
| ③ | 채팅 서버 → RabbitMQ | AMQP | 아웃박스 릴레이가 `feedback.requests`에 발행. 사용자 메시지(role=user)만 대상 |
| ④ | RabbitMQ → Agent | AMQP | Worker가 소비 |
| ⑤ | Agent → business-mcp → DB | MCP | 해당 대화의 최근 메시지(마스킹본) 조회 |
| ⑥ | Agent → LLM | HTTPS | 마스킹된 대화로 프롬프팅 피드백 생성 |
| ⑦ | Agent → business-mcp → DB | MCP | 피드백 저장. `message_id`를 중복 방지 키로 사용 → 저장 후 ack |
| ⑧ | DB | 트리거 | 피드백 INSERT 시 `NOTIFY prompt_feedback, '{feedback_id, user_id}'` |
| ⑨ | DB → Spring | JDBC LISTEN | Spring이 알림 수신 후 피드백 행을 읽음 |
| ⑩ | Spring → 브라우저 | SSE | 해당 사용자의 SSE 연결에 push. 이벤트 ID = `feedback_id` |

**대화 저장(②)은 Agent와 무관하게 먼저 끝난다.** Agent가 멈춰도 대화는 안전하고 피드백만 늦어지며, 밀린 메시지는 큐에 남아 있다가 처리된다.

## 3. RabbitMQ 계약

평가 큐(`evaluation.*`)와 분리한다. 실시간 피드백이 오래 걸리는 평가 작업 뒤에 줄 서지 않게 하기 위해서다.

| 이름 | 종류 | 설정 | 발행 | 소비 |
|---|---|---|---|---|
| `feedback.exchange` | direct | | | |
| `feedback.requests` | queue | routing key `feedback` | 채팅 서버 | Agent |
| `feedback.retry` | queue | `x-message-ttl` 5000ms, DLX → `feedback.exchange`/`feedback` | Agent(일시 실패 시) | 없음(지연 후 복귀) |
| `feedback.dlq` | queue | | Agent(attempt 한도 초과) | 운영 확인 |

- 큐·exchange 선언(Declarables)은 백엔드(Spring)가 기동 시 수행한다. 다른 서비스는 같은 이름·인자로 선언해도 무방하다(동일 인자면 멱등).
- 메시지는 persistent(delivery_mode=2), JSON, UTF-8.

### 메시지 형식 `feedback.requests`
```json
{
  "message_id": "MSG-…",          // 큐 메시지가 아니라 대화 메시지 ID. 중복 방지 키
  "type": "PROMPT_FEEDBACK",
  "conversation_id": "CONV-…",
  "company_id": "C-LGCNS-SALES",
  "user_id": "E-1021",
  "work_id": "W-…",                // [미정] 업무 공간과 연결된 대화면 포함, 아니면 null
  "created_at": "2026-10-22T15:30:00+09:00",
  "attempt": 0
}
```
**대화 본문·개인정보는 넣지 않는다.** Agent는 반드시 MCP로 마스킹본을 읽는다.

### Agent 처리 규칙
- **중복 방지**: `message_id`를 `save_prompt_feedback`의 idempotency_key로 쓴다. 같은 메시지가 두 번 와도 피드백은 한 번만 저장된다.
- **디바운스**: 같은 `conversation_id`에 짧은 간격으로 여러 메시지가 오면 마지막 사용자 메시지에만 피드백한다. 처리 시점에 MCP로 조회한 최신 사용자 메시지가 이 `message_id`가 아니면 피드백을 건너뛰고 ack. **[미정] 간격(예: 3초)**
- **재시도**: MCP·LLM 호출 실패는 Harness가 호출 단위로 재시도 → 소진 시 `attempt+1`로 `feedback.retry` 발행 후 원본 ack → 한도(예: 3) 초과 시 `feedback.dlq`.
- **오래된 요청 폐기**: `created_at`이 일정 시간(예: 5분) 지난 요청은 실시간 의미가 없으므로 건너뛴다. **[미정]**

## 4. DB **[미정 — 테이블 구조 확정 후 갱신]**

필요한 것만 적는다. 실제 이름·컬럼은 MCP 담당이 확정한다.

- **대화**: 채팅 서버가 저장. 원문 `content`와 마스킹본 `content_masked`를 함께 둔다(기존 `messages` 테이블 원칙과 동일).
  **[미정] 마스킹 책임**: (a) 채팅 서버가 저장 시 `content_masked`도 저장 / (b) business-mcp가 조회 시 `policy_rules`로 마스킹. 어느 쪽이든 LLM에는 마스킹본만 간다.
- **피드백** (예: `prompt_feedback`): `feedback_id`, `conversation_id`, `message_id`(UNIQUE), `company_id`, `user_id`, `items jsonb`, `created_at`, `run_id`
- **아웃박스**(채팅 서버 쪽): 대화 저장과 같은 트랜잭션에 기록 → 릴레이가 발행 후 SENT 표시
- **NOTIFY 트리거** (Spring 실시간 감지용):
  ```sql
  CREATE OR REPLACE FUNCTION notify_prompt_feedback() RETURNS trigger AS $$
  BEGIN
    PERFORM pg_notify('prompt_feedback',
      json_build_object('feedback_id', NEW.feedback_id, 'user_id', NEW.user_id)::text);
    RETURN NEW;
  END $$ LANGUAGE plpgsql;

  CREATE TRIGGER trg_prompt_feedback_notify AFTER INSERT ON prompt_feedback
    FOR EACH ROW EXECUTE FUNCTION notify_prompt_feedback();
  ```
  NOTIFY payload에도 본문은 넣지 않는다(ID만).

### business-mcp 도구 (추가 필요)
| 도구 | 입력 | 반환·처리 |
|---|---|---|
| `get_conversation` | conversation_id, (until message_id), limit | 마스킹된 최근 메시지 목록 |
| `save_prompt_feedback` | conversation_id, message_id, items, idempotency_key | 피드백 저장. 같은 키는 기존 결과 반환(`duplicate:true`) |

## 5. 백엔드(Spring) API

| 메서드 | 경로 | 동작 | 권한 |
|---|---|---|---|
| GET | `/api/feedback/stream` | SSE. 로그인 사용자의 새 피드백을 push. `Last-Event-ID` 헤더가 있으면 그 이후 피드백을 먼저 재전송 | 로그인 사용자 본인 |
| GET | `/api/conversations/{conversationId}/feedback` | 대화별 피드백 목록(새로고침·SSE 끊김 복구용) | 대화 당사자 본인 |

SSE 이벤트 예:
```
id: FB-0001
event: feedback
data: {"feedback_id":"FB-0001","conversation_id":"CONV-…","message_id":"MSG-…","items":[…],"created_at":"…"}
```
- 연결 유지용 heartbeat 이벤트를 주기적으로(예: 15초) 보낸다.
- SSE는 알림 수단이고 **정본은 DB**다. 끊기면 목록 API로 다시 읽고 구독을 이어간다.

## 6. 권한·개인정보 원칙
- 실시간 피드백은 **대화 당사자만** 볼 수 있다. 팀장·관리자에게도 공개하지 않는다(기획안: 개인 피드백과 조직 집계 분리).
- 모든 조회에 `company_id` 범위를 적용한다.
- 큐 메시지, NOTIFY payload, 실패 큐(DLQ), 로그에는 대화 원문을 남기지 않는다.
- 채팅 화면과 Spring은 **같은 사용자 식별자(user_id)와 같은 JWT**를 써야 SSE 권한 확인이 된다. **[미정] 채팅 UI가 우리 프론트엔드 안에 있는지**

## 7. 기존 평가 흐름과의 관계
- 업무 완료 → 개인 평가·코칭 흐름(`evaluation.*` 큐)은 그대로 유지한다.
- **팀 평가는 Agent가 생성**한다. Spring은 결과 조회와 팀장 집계 승인만 맡고 직접 계산하지 않는다.
- **[미정]** 실시간 피드백을 받고 프롬프트를 수정한 행동을 업무 평가의 근거로 쓸지. 쓴다면 피드백 자체를 평가 입력 스냅샷에 포함할지 정해야 한다.

## 8. 합의가 필요한 항목
| # | 항목 | 담당 |
|---|---|---|
| 1 | 피드백·대화 테이블 구조 확정 | MCP |
| 2 | 마스킹 책임: 채팅 서버 저장 시 vs business-mcp 조회 시 | 채팅 서버 · MCP |
| 3 | 채팅 서버 아웃박스·`feedback.requests` 발행 구현 | 채팅 서버 |
| 4 | `get_conversation` · `save_prompt_feedback` 도구 추가 | MCP |
| 5 | Agent 피드백 Worker(디바운스·재시도·폐기 기준) | Agent |
| 6 | 디바운스 간격, 오래된 요청 폐기 기준, 재시도 한도 | Agent · 백엔드 |
| 7 | 채팅 UI 위치와 인증(JWT 공유) | 프론트 · 백엔드 |
| 8 | 실시간 피드백의 평가 근거 사용 여부 | 기획 |
