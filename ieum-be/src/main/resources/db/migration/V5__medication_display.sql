-- 약 표시 규칙: docs/이음수첩_AI_개인정보_약등록.md 3.2 기준 (2026-11-21)
-- caution     주의 약 종류 (예: 혈액희석제). null이면 일반 약. 고위험 여부(high_risk)를 대신한다
-- name_hidden 1단계(긴급조회)에서 약 이름을 숨긴다. 주의 약 종류는 그대로 보인다 (화면 L1c)
-- timing      복용 시간 (예: 하루 1번 아침 식후). 약은 이름·용량·복용 시간으로 묶는다
alter table medications add column caution text;
alter table medications add column name_hidden boolean not null default false;
alter table medications add column timing text;
update medications set caution = '주의가 필요한 약' where high_risk;
alter table medications drop column high_risk;
