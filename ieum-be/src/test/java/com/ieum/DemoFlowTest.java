package com.ieum;

import com.ieum.safety.AiQuota;
import com.ieum.safety.IpHasher;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 2단계: 체험 세션·가구 복제·단계 동기화·정리 작업·AI 호출 한도. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DemoFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    AiQuota quota;
    @Autowired
    IpHasher ips;

    @Test
    void demoSession() throws Exception {
        var judge = new TestUser(mvc);
        String first = JsonPath.read(judge.post("/api/demo/sessions", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.step").value(1))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // 복제된 가구로 보호자 API가 그대로 동작
        judge.get("/api/auth/me")
                .andExpect(jsonPath("$.name").value("박지은"))
                .andExpect(jsonPath("$.role").value("primary"))
                .andExpect(jsonPath("$.phone").doesNotExist());
        judge.get("/api/consents").andExpect(jsonPath("$.items.length()").value(5));
        judge.get("/api/guardians").andExpect(jsonPath("$.length()").value(1));

        judge.patch("/api/demo/sessions/me", "{\"step\":2}").andExpect(jsonPath("$.step").value(2));
        judge.patch("/api/demo/sessions/me", "{\"step\":9}").andExpect(status().isBadRequest());
        judge.get("/api/demo/sessions/me").andExpect(jsonPath("$.step").value(2));

        // 처음부터 → 새 가구, 1단계
        String second = JsonPath.read(judge.post("/api/demo/sessions", "")
                .andExpect(jsonPath("$.step").value(1))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertNotEquals(first, second);

        // 24시간이 지나 정리 작업이 돌면 가구째 사라진다
        UUID id = UUID.fromString(second);
        UUID recipient = db.sql("select id from care_recipients where demo_session_id = ?").param(id).query(UUID.class).single();
        assertEquals(3L, count("care_records where recipient_id = ?", recipient));
        db.sql("update demo_sessions set expires_at = now() - interval '1 minute' where id = ?").param(id).update();
        db.sql("select ieum_cleanup()").query().listOfRows();
        assertEquals(0L, count("guardians where demo_session_id = ?", id));
        assertEquals(0L, count("care_records where recipient_id = ?", recipient));
        judge.get("/api/demo/sessions/me").andExpect(status().isNotFound());
        judge.get("/api/auth/me").andExpect(status().isUnauthorized());

        // 실제 보호자는 체험 세션이 없다
        new TestUser(mvc).signup("010-7777-8888", "실사용자")
                .get("/api/demo/sessions/me").andExpect(status().isNotFound());

        // IP당 하루 100개
        String myIp = ips.hash("127.0.0.1");
        db.sql("insert into demo_sessions (ip_hash) select ? from generate_series(1, 100)").param(myIp).update();
        new TestUser(mvc).post("/api/demo/sessions", "").andExpect(status().isTooManyRequests());
        db.sql("delete from demo_sessions where ip_hash = ?").param(myIp).update();
    }

    @Test
    void aiQuota() {
        UUID session = db.sql("insert into demo_sessions default values returning id").query(UUID.class).single();
        for (int i = 0; i < 5; i++) {
            assertTrue(quota.allows(session, "ip-a"));
            quota.record(session, "ip-a", "draft", "test", "ok");
        }
        assertFalse(quota.allows(session, "ip-a"), "체험 세션당 5회");
        assertTrue(quota.allows(null, "ip-a"), "실제 보호자는 세션 한도 없음");

        addCalls("ip-a", 15);
        assertFalse(quota.allows(null, "ip-a"), "IP당 하루 20회");
        assertTrue(quota.allows(null, "ip-b"));

        addCalls("ip-many", 280);
        assertFalse(quota.allows(null, "ip-c"), "전체 하루 300회");

        db.sql("update ai_calls set created_at = now() - interval '25 hours'").update();
        assertTrue(quota.allows(session, "ip-a"), "하루가 지나면 풀린다");
        db.sql("delete from ai_calls").update();
    }

    private long count(String fromWhere, Object param) {
        return db.sql("select count(*) from " + fromWhere).param(param).query(Long.class).single();
    }

    private void addCalls(String ipHash, int n) {
        db.sql("insert into ai_calls (ip_hash, kind, status) select ?, 'draft', 'ok' from generate_series(1, ?)")
                .params(ipHash, n).update();
    }
}
