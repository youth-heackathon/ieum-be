-- 이음수첩 초기 스키마 (요구사항 3장 초안)
-- 보호대상자·체험 세션을 지우면 딸린 행이 함께 지워지도록 ON DELETE CASCADE를 건다.
-- → 체험 24시간 삭제는 `delete from demo_sessions where expires_at < now()` 한 줄이면 된다.

create table demo_sessions (
    id                uuid primary key default gen_random_uuid(),
    step              int not null default 1,
    direct_call_count int not null default 0,
    created_at        timestamptz not null default now(),
    expires_at        timestamptz not null default now() + interval '24 hours'
);

create table guardians (
    id              uuid primary key default gen_random_uuid(),
    name            text not null,
    phone           text unique,                 -- 체험 가구는 null
    birth_date      date,
    demo_session_id uuid references demo_sessions (id) on delete cascade,
    created_at      timestamptz not null default now()
);

create table care_recipients (
    id                 uuid primary key default gen_random_uuid(),
    name               text not null,
    birth_date         date,                     -- 등록 항목은 [미정]
    self_consent       boolean not null default false, -- 본인 동의
    guardian_of_record boolean not null default false, -- 후견인 있음
    demo_session_id    uuid references demo_sessions (id) on delete cascade,
    created_at         timestamptz not null default now()
);

create table recipient_guardians (
    recipient_id uuid not null references care_recipients (id) on delete cascade,
    guardian_id  uuid not null references guardians (id) on delete cascade,
    role         text not null check (role in ('primary', 'member')),
    joined_at    timestamptz not null default now(),
    consent_at   timestamptz not null,
    primary key (recipient_id, guardian_id)
);
-- [가정] 보호자 1명당 보호대상자 1명. 여러 명을 허용하면 이 인덱스를 지운다
create unique index recipient_guardians_one_per_guardian on recipient_guardians (guardian_id);

create table guardian_invites (
    id           uuid primary key default gen_random_uuid(),
    recipient_id uuid not null references care_recipients (id) on delete cascade,
    code         text not null unique,
    issued_by    uuid not null references guardians (id) on delete cascade,
    expires_at   timestamptz not null,
    used_at      timestamptz,
    used_by      uuid references guardians (id) on delete set null,
    created_at   timestamptz not null default now()
);

create table consents (
    recipient_id uuid not null references care_recipients (id) on delete cascade,
    item         text not null check (item in ('PERSONAL', 'HEALTH', 'EMERGENCY', 'DETAIL', 'OVERSEAS_AI')),
    agreed       boolean not null,
    agreed_at    timestamptz not null default now(),
    agreed_by    uuid references guardians (id) on delete set null,
    primary key (recipient_id, item)
);

create table access_codes (
    id           uuid primary key default gen_random_uuid(),
    recipient_id uuid not null references care_recipients (id) on delete cascade,
    qr_token     text not null unique,
    code8        char(8) not null,
    issued_at    timestamptz not null default now(),
    revoked_at   timestamptz
);
create unique index access_codes_active_code8 on access_codes (code8) where revoked_at is null;
create unique index access_codes_active_per_recipient on access_codes (recipient_id) where revoked_at is null;

create table access_logs (
    id             uuid primary key default gen_random_uuid(),
    access_code_id uuid not null references access_codes (id) on delete cascade,
    kind           text not null check (kind in ('emergency', 'detail_request', 'detail_view')),
    user_agent     text,
    ip_hash        text,
    created_at     timestamptz not null default now()
);

create table detail_requests (
    id              uuid primary key default gen_random_uuid(),
    recipient_id    uuid not null references care_recipients (id) on delete cascade,
    access_code_id  uuid not null references access_codes (id) on delete cascade,
    requester_phone text,                        -- 선택 입력, 30일 뒤 파기
    phone_purge_at  timestamptz,
    user_agent      text,
    ip_hash         text,
    status          text not null default 'pending' check (status in ('pending', 'approved', 'rejected', 'expired')),
    deadline_at     timestamptz not null,
    decided_by      uuid references guardians (id) on delete set null,
    decided_at      timestamptz,
    view_token      text unique,
    view_expires_at timestamptz,
    created_at      timestamptz not null default now()
);

create table care_records (
    id                uuid primary key default gen_random_uuid(),
    recipient_id      uuid not null references care_recipients (id) on delete cascade,
    category          text not null,
    summary           text not null,
    quote_text        text,
    verify_status     text not null check (verify_status in ('pass', 'review', 'blocked')),
    emergency_visible boolean not null default false,
    confirmed_at      timestamptz,               -- null = 보호자 확인 전 임시
    last_checked_at   timestamptz,
    created_at        timestamptz not null default now()
);

create table drug_master (
    code       text primary key,                 -- 심평원 의약품표준코드
    name       text not null,
    ingredient text,
    high_risk  boolean not null default false
);

create table medication_sources (
    id                 uuid primary key default gen_random_uuid(),
    recipient_id       uuid not null references care_recipients (id) on delete cascade,
    masked_image       bytea,                    -- 가린 사진만 받는다
    masked_image_type  text,
    opus_raw           jsonb,
    created_at         timestamptz not null default now()
);

create table medications (
    id                uuid primary key default gen_random_uuid(),
    recipient_id      uuid not null references care_recipients (id) on delete cascade,
    source_id         uuid references medication_sources (id) on delete set null,
    drug_code         text,                      -- drug_master.code (재적재를 막지 않도록 FK 없음)
    name              text not null,
    dose              text,
    quote_text        text,
    verify_status     text check (verify_status in ('pass', 'review', 'blocked')),
    ai_source         text,
    emergency_visible boolean not null default false,
    high_risk         boolean not null default false,
    pharmacy_phone    text,
    confirmed_at      timestamptz,               -- 용량은 보호자 확인 전까지 확정 아님
    created_at        timestamptz not null default now()
);

create table notifications (
    id          uuid primary key default gen_random_uuid(),
    guardian_id uuid not null references guardians (id) on delete cascade,
    kind        text not null,
    ref_id      uuid,
    read_at     timestamptz,
    created_at  timestamptz not null default now()
);

create table ai_calls (
    id         uuid primary key default gen_random_uuid(),
    session_id uuid references demo_sessions (id) on delete set null,
    ip_hash    text,
    kind       text not null,
    model      text,
    status     text not null,                    -- 입력 원문은 저장하지 않는다
    created_at timestamptz not null default now()
);
