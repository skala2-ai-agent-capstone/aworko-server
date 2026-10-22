-- 로그인: 사번(employee_no) + 비밀번호(평문, 단순 프로젝트). 역할: member / team_lead.
-- MCP 쪽 001_schema.sql에 반영되기 전까지 백엔드가 임시로 추가한다 (docs/db-schema.json 기준).
-- MCP 적재기(db-init)는 users를 위치 기반 INSERT + ON CONFLICT UPDATE(role 포함)로 쓰므로
-- 끝에 붙인 두 컬럼은 유지되지만 role은 시드 값(employee 등)으로 되돌아갈 수 있다 → UserRole 변환기가 흡수한다.

ALTER TABLE users ADD COLUMN IF NOT EXISTS employee_no int;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password text;
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_employee_no ON users (employee_no);

-- 기존 CHECK(employee, team_lead, reviewer, ax_manager, admin)에 member 추가
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
  CHECK (role IN ('member','team_lead','employee','reviewer','ax_manager','admin'));

-- 시드 사용자 채우기: 사번 = user_id의 숫자 부분(E-1021 → 1021), 비밀번호 = 1234
UPDATE users SET employee_no = substring(user_id FROM '[0-9]+')::int
 WHERE employee_no IS NULL AND user_id ~ '[0-9]+';
UPDATE users SET password = '1234' WHERE password IS NULL;
UPDATE users SET role = 'member' WHERE role <> 'team_lead';
