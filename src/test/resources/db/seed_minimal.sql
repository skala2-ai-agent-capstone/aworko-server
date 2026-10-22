-- 영속성 테스트용 최소 데이터 (시드 organization.json 일부와 같은 ID)
INSERT INTO companies (company_id, name) VALUES ('C-LGCNS-SALES', '테스트 회사') ON CONFLICT DO NOTHING;
INSERT INTO teams (team_id, company_id, name) VALUES ('T-SALES-01', 'C-LGCNS-SALES', '영업1팀') ON CONFLICT DO NOTHING;
INSERT INTO users (user_id, company_id, team_id, name, role, employee_no, password) VALUES
  ('E-1021', 'C-LGCNS-SALES', 'T-SALES-01', '가상 직원 A', 'member', 1021, '1234'),
  ('L-3001', 'C-LGCNS-SALES', 'T-SALES-01', '가상 팀장', 'team_lead', 3001, '1234'),
  ('AD-4001', 'C-LGCNS-SALES', NULL, '이전 시드 관리자', 'admin', 4001, '1234')
ON CONFLICT DO NOTHING;
INSERT INTO customers (customer_id, company_id, name) VALUES ('CU-01', 'C-LGCNS-SALES', '가상 고객') ON CONFLICT DO NOTHING;
INSERT INTO documents (doc_id, version, company_id, title, grade, collection, indexed, status, effective_from, work_type) VALUES
  ('RUB-quote', 'v2', 'C-LGCNS-SALES', '견적서 루브릭', '사내', 'rubrics', true, '현행', '2026-01-01', 'quote')
ON CONFLICT DO NOTHING;
INSERT INTO work_cases (work_id, company_id, team_id, employee_id, work_type, customer_id, status, created_at) VALUES
  ('W-TEST-QT-01', 'C-LGCNS-SALES', 'T-SALES-01', 'E-1021', 'quote', 'CU-01', 'completed', now())
ON CONFLICT DO NOTHING;
INSERT INTO work_cases (work_id, company_id, team_id, employee_id, title, work_type, customer_id, status, created_at) VALUES
  ('W-TEST-NEW-01', 'C-LGCNS-SALES', 'T-SALES-01', 'E-1021', '유형 미정 업무', NULL, 'CU-01', 'in_progress', now())
ON CONFLICT DO NOTHING;
INSERT INTO artifact_versions (artifact_id, version, work_id, author, saved_at, content, content_masked) VALUES
  ('ART-TEST-QT-01', 'v2', 'W-TEST-QT-01', 'employee', now(), '견적서 본문', '견적서 본문')
ON CONFLICT DO NOTHING;
INSERT INTO policy_rules (rule_id, seq, kind, pattern, replace_with) VALUES
  ('MASK-PHONE', 1, 'masking', '01[016789]-\d{3,4}-\d{4}', '[전화번호]')
ON CONFLICT DO NOTHING;
