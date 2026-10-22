-- 실시간 피드백 요청(LIVE_EVALUATE) 메시지를 아웃박스로 발행한다.
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS outbox_events_type_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_type_check
  CHECK (type IN ('EVALUATE','RESUME','LIVE_EVALUATE'));
