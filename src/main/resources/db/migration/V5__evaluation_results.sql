-- 최종 평가 완료 알림(FINAL_EVALUATION_SAVED) 수신 기록. Agent 최종 평가 Worker(backend-v1)가
-- 초안·코칭 저장 뒤 evaluation.results로 보내고, 백엔드가 영수증을 확인한 뒤 평가 건을 '확인 대기'로 바꾼다.
-- message_id로 중복 수신을 막는다(재전달마다 같은 ID). outcome은 적용 여부:
--   APPLIED          확인 대기로 바꿈
--   STALE            이미 더 뒤 상태(확인 대기·검토·확정·실패 등)라 바꾸지 않음
--   RECEIPT_MISMATCH 알림의 초안·코칭 판(revision)이 DB에 없음
CREATE TABLE IF NOT EXISTS evaluation_result_receipts (
  message_id         text        PRIMARY KEY,
  evaluation_id      text        NOT NULL,
  operation_id       text        NOT NULL,
  draft_revision     int         NOT NULL,
  coaching_revision  int         NOT NULL,
  review_required    boolean     NOT NULL,
  outcome            text        NOT NULL CHECK (outcome IN ('APPLIED','STALE','RECEIPT_MISMATCH')),
  received_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_eval_result_receipts_eval ON evaluation_result_receipts (evaluation_id, received_at);

-- RESUME은 더 이상 큐로 보내지 않는다(본인 확인·이의·검토 결정은 백엔드 트랜잭션에서 처리).
-- 아직 안 나간 RESUME 행은 발행하지 않고 DISCARDED로 남긴다. type 제약은 V4 그대로 둔다(예전 행 보존).
ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS outbox_events_status_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_status_check
  CHECK (status IN ('PENDING','SENT','DISCARDED'));
UPDATE outbox_events
   SET status = 'DISCARDED', last_error = 'RESUME 폐지: 백엔드 트랜잭션에서 처리'
 WHERE type = 'RESUME' AND status = 'PENDING';
