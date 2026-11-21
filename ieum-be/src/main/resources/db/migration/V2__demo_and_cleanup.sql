-- 체험 세션: IP당 생성 횟수 제한용 칼럼. 세션당 AI 호출 수는 ai_calls로 세므로 카운터 칼럼은 뺀다
alter table demo_sessions add column ip_hash text;
alter table demo_sessions drop column direct_call_count;

-- 보관·삭제 (FR-12). 체험 데이터는 demo_sessions에서 cascade로 함께 지워진다
create function ieum_cleanup() returns void language sql as $$
    delete from demo_sessions where expires_at < now();
    update detail_requests set requester_phone = null, phone_purge_at = null where phone_purge_at < now();
$$;

-- Supabase에서는 pg_cron으로 10분마다 돌린다. 로컬·테스트 Postgres에는 pg_cron이 없어 건너뛴다
do $$
begin
    create extension if not exists pg_cron;
    perform cron.schedule('ieum-cleanup', '*/10 * * * *', 'select ieum_cleanup()');
exception when others then
    raise notice 'pg_cron 없음, 정리 작업 예약을 건너뜀: %', sqlerrm;
end $$;
