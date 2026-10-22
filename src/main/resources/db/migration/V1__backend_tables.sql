-- 백엔드 전용 테이블. 기존 42개 테이블(ai-agent-capstone/mcp_servers/db/postgres/001_schema.sql)은 건드리지 않는다.
-- 이력 테이블은 flyway_backend_history (application.yml)

-- 평가 요청·재개 메시지 발행 대기열 (transactional outbox).
-- evaluations 생성·상태 변경과 같은 트랜잭션에 기록하고, 릴레이가 RabbitMQ로 발행한다.
CREATE TABLE IF NOT EXISTS outbox_events (
  id             bigserial   PRIMARY KEY,
  aggregate_id   text        NOT NULL,                 -- evaluation_id
  type           text        NOT NULL CHECK (type IN ('EVALUATE','RESUME')),
  routing_key    text        NOT NULL,
  payload        jsonb       NOT NULL,                 -- 식별자·버전만. 업무 원문·개인정보 금지
  status         text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT')),
  attempts       int         NOT NULL DEFAULT 0,
  last_error     text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  sent_at        timestamptz
);
CREATE INDEX IF NOT EXISTS ix_outbox_pending ON outbox_events (id) WHERE status = 'PENDING';

-- DLQ로 간 메시지 보존. 백엔드 DlqListener가 기록하고 운영자가 재처리한다.
CREATE TABLE IF NOT EXISTS evaluation_failures (
  id              bigserial   PRIMARY KEY,
  evaluation_id   text        NOT NULL REFERENCES evaluations ON DELETE CASCADE,
  message         jsonb       NOT NULL,
  reason          text,
  failed_at       timestamptz NOT NULL DEFAULT now(),
  reprocessed_at  timestamptz,
  reprocessed_by  text        REFERENCES users
);
CREATE INDEX IF NOT EXISTS ix_eval_failures_eval ON evaluation_failures (evaluation_id, failed_at);

-- 팀장의 팀 집계 승인 (업무 유형·기간별). snapshot은 승인 시점의 집계 결과
CREATE TABLE IF NOT EXISTS aggregation_approvals (
  id           bigserial   PRIMARY KEY,
  company_id   text        NOT NULL REFERENCES companies,
  team_id      text        NOT NULL REFERENCES teams,
  work_type    text        NOT NULL CHECK (work_type IN ('rfp_analysis','proposal','quote','inquiry_reply')),
  period_from  date        NOT NULL,
  period_to    date        NOT NULL,
  approved_by  text        NOT NULL REFERENCES users,
  approved_at  timestamptz NOT NULL DEFAULT now(),
  snapshot     jsonb       NOT NULL,
  CHECK (period_from <= period_to)
);
CREATE INDEX IF NOT EXISTS ix_agg_approvals_team ON aggregation_approvals (team_id, work_type, approved_at);
