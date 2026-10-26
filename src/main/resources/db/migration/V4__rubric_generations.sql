-- 내규 업로드 뒤 평가 기준 초안 자동 생성(RUBRIC_GENERATE) 요청과 결과. 운영 설정 화면이 상태를 폴링한다.
-- Agent(기준 생성 Worker)가 business-mcp save_rubric으로 초안을 저장하고, 완료는 rubric_generation.results로,
-- 실패는 rubric_generation.dlq로 알린다. 상태 전환은 백엔드만 한다.
CREATE TABLE IF NOT EXISTS rubric_generations (
  generation_id   text        PRIMARY KEY,
  company_id      text        NOT NULL REFERENCES companies,
  policy_doc_id   text        NOT NULL,              -- 생성 입력으로 고정한 내규 버전
  policy_version  text        NOT NULL,
  source_file     text,
  work_types      text[]      NOT NULL,
  status          text        NOT NULL DEFAULT '대기' CHECK (status IN ('대기','완료','실패')),
  drafts          jsonb,                             -- 완료: [{work_type, doc_id, version}]
  error           text,                              -- 실패: Worker 오류 코드(원문 없음)
  requested_by    text        NOT NULL REFERENCES users,
  created_at      timestamptz NOT NULL DEFAULT now(),
  finished_at     timestamptz
);
CREATE INDEX IF NOT EXISTS ix_rubric_generations_company ON rubric_generations (company_id, created_at DESC);
-- 회사마다 진행 중인 생성은 하나(초안은 회사·유형마다 하나라 동시에 돌면 서로 덮어쓴다)
CREATE UNIQUE INDEX IF NOT EXISTS ux_rubric_generations_pending ON rubric_generations (company_id) WHERE status = '대기';

ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS outbox_events_type_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_type_check
  CHECK (type IN ('EVALUATE','RESUME','LIVE_EVALUATE','RUBRIC_GENERATE'));
