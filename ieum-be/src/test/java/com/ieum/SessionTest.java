package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("세션 (DB 저장, 재배포해도 유지)")
class SessionTest extends ApiTest {

    List<String> attributes(String sessionId) {
        return db.sql("""
                        select a.attribute_name from spring_session_attributes a
                        join spring_session s on s.primary_id = a.session_primary_id where s.session_id = ? order by 1""")
                .param(sessionId).query(String.class).list();
    }

    @Test
    @DisplayName("로그인하면 세션이 DB에 저장된다 → 서버를 다시 띄워도 로그인이 유지된다")
    void loginStoredInDb() throws Exception {
        TestUser u = guardian("박지은");
        assertEquals(1L, count("spring_session where session_id = ?", u.sessionId()));
        assertTrue(attributes(u.sessionId()).contains("guardianId"));
    }

    @Test
    @DisplayName("체험 세션도 DB에 저장돼 심사 중 재배포해도 체험이 끊기지 않는다")
    void demoStoredInDb() throws Exception {
        TestUser judge = judge();
        assertEquals(List.of("demoSessionId", "guardianId"), attributes(judge.sessionId()));
    }

    @Test
    @DisplayName("로그아웃하면 DB에서도 세션이 지워진다")
    void logoutDeletes() throws Exception {
        TestUser u = guardian("박지은");
        String id = u.sessionId();
        u.post("/api/auth/logout", "");
        assertEquals(0L, count("spring_session where session_id = ?", id));
        assertNull(u.sessionId(), "브라우저 쿠키도 지운다");
    }

    @Test
    @DisplayName("세션 쿠키는 HttpOnly·SameSite=Lax다")
    void cookieFlags() throws Exception {
        var cookie = guardian("박지은").sessionCookie();
        assertTrue(cookie.isHttpOnly());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
    }

    @Test
    @DisplayName("조회하는 쪽(로그인 없음)은 세션을 만들지 않는다")
    void publicCreatesNoSession() throws Exception {
        String token = qrToken(judge());
        TestUser medic = anonymous();
        medic.post("/api/public/access/" + token + "/emergency", "").andExpect(status().isOk());
        medic.post("/api/public/access/" + token + "/detail-requests", "").andExpect(status().isCreated());
        assertNull(medic.sessionId());
    }
}
