package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("보관·삭제 (ieum_cleanup, pg_cron이 10분마다 실행)")
class CleanupTest extends ApiTest {

    void cleanup() {
        db.sql("select ieum_cleanup()").query().listOfRows();
    }

    UUID startDemo() throws Exception {
        TestUser u = anonymous();
        return UUID.fromString(read(u.post("/api/demo/sessions", ""), "$.id"));
    }

    @Test
    @DisplayName("24시간 지난 체험 가구는 딸린 데이터까지 모두 지운다")
    void expiredDemoDeleted() throws Exception {
        UUID s = startDemo();
        UUID r = db.sql("select id from care_recipients where demo_session_id = ?").param(s).query(UUID.class).single();
        db.sql("update demo_sessions set expires_at = now() - interval '1 second' where id = ?").param(s).update();
        cleanup();
        assertEquals(0L, count("demo_sessions where id = ?", s));
        assertEquals(0L, count("guardians where demo_session_id = ?", s));
        assertEquals(0L, count("care_recipients where id = ?", r));
        assertEquals(0L, count("recipient_guardians where recipient_id = ?", r));
        assertEquals(0L, count("consents where recipient_id = ?", r));
        assertEquals(0L, count("care_records where recipient_id = ?", r));
        assertEquals(0L, count("medications where recipient_id = ?", r));
    }

    @Test
    @DisplayName("아직 24시간이 안 된 체험은 남긴다")
    void freshDemoKept() throws Exception {
        UUID s = startDemo();
        cleanup();
        assertEquals(1L, count("demo_sessions where id = ?", s));
        assertEquals(1L, count("guardians where demo_session_id = ?", s));
    }

    @Test
    @DisplayName("실제 보호자 데이터는 지우지 않는다")
    void realDataKept() throws Exception {
        UUID r = UUID.fromString(read(primary("박지은").get("/api/auth/me"), "$.recipientId"));
        db.sql("update demo_sessions set expires_at = now() - interval '1 second'").update(); // 모든 체험 만료
        cleanup();
        assertEquals(1L, count("care_recipients where id = ?", r));
        assertEquals(1L, count("recipient_guardians where recipient_id = ?", r));
    }

    @Test
    @DisplayName("파기 기한이 지난 요청자 번호는 지우고, 기한 전 번호는 남긴다")
    void requesterPhonePurged() throws Exception {
        UUID r = UUID.fromString(read(primary("박지은").get("/api/auth/me"), "$.recipientId"));
        UUID code = db.sql("insert into access_codes (recipient_id, qr_token, code8) values (?, ?, ?) returning id")
                .params(r, UUID.randomUUID().toString(), uniquePhone().substring(3)).query(UUID.class).single();
        String insert = """
                insert into detail_requests (recipient_id, access_code_id, requester_phone, phone_purge_at, deadline_at)
                values (?, ?, '01099998888', now() + ?::interval, now()) returning id""";
        UUID old = db.sql(insert).params(r, code, "-1 second").query(UUID.class).single();
        UUID recent = db.sql(insert).params(r, code, "29 days").query(UUID.class).single();

        cleanup();

        assertTrue(db.sql("select requester_phone is null from detail_requests where id = ?").param(old).query(Boolean.class).single());
        assertEquals("01099998888", db.sql("select requester_phone from detail_requests where id = ?").param(recent).query(String.class).single());
        assertEquals(1L, count("detail_requests where id = ?", old), "요청 기록 자체는 남는다");
    }
}
