-- 8자리 코드 무작위 대입 방지: 틀린 시도만 IP 해시로 기록한다
create table code_attempts (
    ip_hash    text not null,
    created_at timestamptz not null default now()
);

-- 정리 작업에 상세조회 만료 표시와 오래된 시도 기록 삭제를 더한다
-- (만료 판정 자체는 읽을 때 서버 시각으로 한다. 이건 상태 칼럼을 맞춰 두는 정리일 뿐)
create or replace function ieum_cleanup() returns void language sql as $$
    delete from demo_sessions where expires_at < now();
    update detail_requests set requester_phone = null, phone_purge_at = null where phone_purge_at < now();
    update detail_requests set status = 'expired' where status = 'pending' and deadline_at <= now();
    delete from code_attempts where created_at < now() - interval '1 day';
$$;
