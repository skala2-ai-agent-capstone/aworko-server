-- 팀 평가 실행 실패 기록. 요청·스냅샷·리포트는 business-mcp가 team_evaluations(MCP 스키마)에 둔다.
-- 팀 Agent Worker가 재시도 한도를 넘기거나 처리할 수 없는 요청을 team_evaluation.dlq로 보내면 백엔드가 남긴다.
-- 화면 상태: team_evaluations.status='완료'면 완료, 여기 행이 있으면 실패, 그 밖은 대기.
CREATE TABLE IF NOT EXISTS team_evaluation_failures (
  id          bigserial   PRIMARY KEY,
  request_id  uuid        NOT NULL,
  reason      text,                                   -- Worker error_code 헤더(원문 없음)
  message     jsonb       NOT NULL,
  failed_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_team_eval_failures_request ON team_evaluation_failures (request_id, failed_at);

ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS outbox_events_type_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_type_check
  CHECK (type IN ('EVALUATE','RESUME','LIVE_EVALUATE','RUBRIC_GENERATE','TEAM_EVALUATE'));
