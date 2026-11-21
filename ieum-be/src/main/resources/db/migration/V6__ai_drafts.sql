-- AI 단계: 흐린 용량 표시(확인 필요)와 당사자용 쉬운 말
alter table medications add column dose_needs_check boolean not null default false;
alter table medications add column easy_text text;
