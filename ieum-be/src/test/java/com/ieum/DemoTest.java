package com.ieum;

import com.ieum.safety.IpHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("심사위원 체험 모드")
class DemoTest extends ApiTest {

    @Autowired
    IpHasher ips;

    TestUser judge() throws Exception {
        TestUser u = anonymous();
        u.post("/api/demo/sessions", "").andExpect(status().isCreated());
        return u;
    }

    UUID recipientOf(TestUser u) throws Exception {
        return UUID.fromString(read(u.get("/api/auth/me"), "$.recipientId"));
    }

    @Test
    @DisplayName("체험을 시작하면 1단계에서 24시간짜리 세션이 열린다")
    void start() throws Exception {
        anonymous().post("/api/demo/sessions", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.step").value(1))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("체험을 시작하면 가상 가구의 보호자 박지은으로 로그인된다 (전화번호 없음)")
    void loggedInAsPersona() throws Exception {
        judge().get("/api/auth/me")
                .andExpect(jsonPath("$.name").value("박지은"))
                .andExpect(jsonPath("$.role").value("primary"))
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    @DisplayName("가상 가구에는 동의 5항목, 확인된 기록 3건, 약 2건이 들어 있다")
    void householdSeeded() throws Exception {
        TestUser u = judge();
        u.get("/api/consents")
                .andExpect(jsonPath("$.selfConsent").value(true))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[?(@.agreed==false)]").isEmpty());
        UUID r = recipientOf(u);
        assertEquals(3L, count("care_records where recipient_id = ? and confirmed_at is not null and verify_status = 'pass'", r));
        assertEquals(1L, count("care_records where recipient_id = ? and emergency_visible", r));
        assertEquals(2L, count("medications where recipient_id = ? and emergency_visible", r));
        assertEquals(1L, count("medications where recipient_id = ? and high_risk", r));
    }

    @Test
    @DisplayName("단계를 1~5로 바꿀 수 있고 다시 조회해도 그대로다")
    void moveStep() throws Exception {
        TestUser u = judge();
        for (int step = 1; step <= 5; step++) {
            u.patch("/api/demo/sessions/me", "{\"step\":%d}".formatted(step)).andExpect(jsonPath("$.step").value(step));
            u.get("/api/demo/sessions/me").andExpect(jsonPath("$.step").value(step));
        }
    }

    @Test
    @DisplayName("범위 밖 단계나 빈 값은 400이고 단계는 바뀌지 않는다")
    void invalidStep() throws Exception {
        TestUser u = judge();
        u.patch("/api/demo/sessions/me", "{\"step\":0}").andExpect(status().isBadRequest());
        u.patch("/api/demo/sessions/me", "{\"step\":6}").andExpect(status().isBadRequest());
        u.patch("/api/demo/sessions/me", "{}").andExpect(status().isBadRequest());
        u.get("/api/demo/sessions/me").andExpect(jsonPath("$.step").value(1));
    }

    @Test
    @DisplayName("처음부터를 누르면 새 가구가 만들어지고 1단계로 돌아간다")
    void restart() throws Exception {
        TestUser u = judge();
        u.patch("/api/demo/sessions/me", "{\"step\":4}");
        UUID before = recipientOf(u);
        u.post("/api/demo/sessions", "").andExpect(jsonPath("$.step").value(1));
        assertNotEquals(before, recipientOf(u));
    }

    @Test
    @DisplayName("심사위원끼리 가구가 섞이지 않는다")
    void isolated() throws Exception {
        TestUser a = judge(), b = judge();
        assertNotEquals(recipientOf(a), recipientOf(b));
        a.post("/api/consents", "{\"items\":{\"DETAIL\":false}}");
        b.get("/api/consents").andExpect(jsonPath("$.items[?(@.item=='DETAIL')].agreed").value(true));
        a.get("/api/guardians").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("체험 중에 전화번호로 로그인하면 체험이 끝난다")
    void loginEndsDemo() throws Exception {
        TestUser u = judge();
        u.post("/api/auth/otp/verify", "{\"phone\":\"%s\",\"code\":\"000000\"}".formatted(uniquePhone()));
        u.post("/api/auth/signup", "{\"name\":\"실사용자\",\"birthDate\":\"1980-01-01\"}");
        u.get("/api/demo/sessions/me").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("실제 보호자에게는 체험 세션이 없다")
    void realGuardianHasNoDemo() throws Exception {
        primary("박지은").get("/api/demo/sessions/me")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("체험 세션이 없거나 끝났어요. 처음부터 다시 시작해 주세요"));
    }

    @Test
    @DisplayName("24시간이 지나 정리되면 체험 화면은 처음부터 안내를 받는다")
    void expired() throws Exception {
        TestUser u = judge();
        db.sql("update demo_sessions set expires_at = now() - interval '1 minute' where id = ?")
                .param(UUID.fromString(read(u.get("/api/demo/sessions/me"), "$.id"))).update();
        db.sql("select ieum_cleanup()").query().listOfRows();
        u.get("/api/demo/sessions/me").andExpect(status().isNotFound());
        u.get("/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("같은 IP에서 하루 100번 넘게 시작하면 429다")
    void perIpLimit() throws Exception {
        String myIp = ips.hash("127.0.0.1");
        long already = count("demo_sessions where ip_hash = ? and created_at > now() - interval '1 day'", myIp);
        db.sql("insert into demo_sessions (ip_hash) select ? from generate_series(1, ?)").params(myIp, 100 - already).update();
        try {
            anonymous().post("/api/demo/sessions", "").andExpect(status().isTooManyRequests());
        } finally {
            db.sql("delete from demo_sessions where ip_hash = ?").param(myIp).update();
        }
    }
}
