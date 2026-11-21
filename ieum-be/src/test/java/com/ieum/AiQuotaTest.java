package com.ieum;

import com.ieum.safety.AiQuota;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AI 호출 한도: 체험 세션당 5회·IP당 하루 20회·전체 하루 300회")
class AiQuotaTest extends ApiTest {

    @Autowired
    AiQuota quota;
    UUID session;

    @BeforeEach
    @AfterEach
    void reset() {
        db.sql("delete from ai_calls").update(); // 전체 한도는 다른 테스트와 겹치면 안 된다
        session = db.sql("insert into demo_sessions default values returning id").query(UUID.class).single();
    }

    void calls(UUID session, String ipHash, int n) {
        for (int i = 0; i < n; i++) quota.record(session, ipHash, "draft", "test", "ok");
    }

    @Test
    @DisplayName("체험 세션은 5회까지 부를 수 있다")
    void perSession() {
        calls(session, "ip-a", 4);
        assertTrue(quota.allows(session, "ip-a"));
        calls(session, "ip-a", 1);
        assertFalse(quota.allows(session, "ip-a"));
    }

    @Test
    @DisplayName("세션 한도는 그 세션에만 걸리고, 실제 보호자(세션 없음)에게는 없다")
    void sessionLimitIsScoped() {
        calls(session, "ip-a", 5);
        UUID other = db.sql("insert into demo_sessions default values returning id").query(UUID.class).single();
        assertTrue(quota.allows(other, "ip-b"));
        assertTrue(quota.allows(null, "ip-a"));
    }

    @Test
    @DisplayName("IP당 하루 20회까지이고 다른 IP는 영향받지 않는다")
    void perIp() {
        calls(null, "ip-a", 19);
        assertTrue(quota.allows(null, "ip-a"));
        calls(null, "ip-a", 1);
        assertFalse(quota.allows(null, "ip-a"));
        assertTrue(quota.allows(null, "ip-b"));
    }

    @Test
    @DisplayName("전체 하루 300회를 넘으면 누구도 부를 수 없다")
    void global() {
        db.sql("insert into ai_calls (ip_hash, kind, status) select 'ip-' || g, 'draft', 'ok' from generate_series(1, 299) g").update();
        assertTrue(quota.allows(null, "ip-new"));
        calls(null, "ip-last", 1);
        assertFalse(quota.allows(null, "ip-new"));
    }

    @Test
    @DisplayName("24시간이 지난 호출은 세지 않는다")
    void rollingWindow() {
        calls(session, "ip-a", 20);
        db.sql("update ai_calls set created_at = now() - interval '25 hours'").update();
        assertTrue(quota.allows(session, "ip-a"));
    }
}
