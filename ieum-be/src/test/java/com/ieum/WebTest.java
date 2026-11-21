package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("공통: 헬스체크·접근 제어·CORS")
class WebTest extends ApiTest {

    @Test
    @DisplayName("헬스체크는 DB까지 확인하고 ok를 돌려준다")
    void health() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("로그인하지 않으면 보호자 API는 모두 401이다")
    void guardianApisNeedLogin() throws Exception {
        TestUser anon = anonymous();
        anon.get("/api/guardians").andExpect(status().isUnauthorized());
        anon.get("/api/consents").andExpect(status().isUnauthorized());
        anon.post("/api/recipients", "{\"name\":\"어머니\"}").andExpect(status().isUnauthorized());
        anon.post("/api/guardians/invite-codes", "").andExpect(status().isUnauthorized());
        anon.get("/api/demo/sessions/me").andExpect(status().isUnauthorized());
        anon.get("/api/auth/me").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("로그인이 필요해요"));
    }

    @Test
    @DisplayName("허용한 프론트 도메인은 쿠키를 실은 요청의 preflight를 통과한다")
    void corsAllowed() throws Exception {
        mvc.perform(options("/api/guardians")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("허용하지 않은 도메인은 preflight에서 막힌다")
    void corsBlocked() throws Exception {
        mvc.perform(options("/api/guardians")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
