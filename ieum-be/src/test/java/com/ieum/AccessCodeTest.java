package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("QR·8자리 연결코드")
class AccessCodeTest extends ApiTest {

    static final String RESOLVE = "/api/public/access/resolve";

    static String byCode(String code) {
        return "{\"code\":\"%s\"}".formatted(code);
    }

    static String byToken(String token) {
        return "{\"token\":\"%s\"}".formatted(token);
    }

    static String wrongCode(String real) {
        return real.equals("00000000") ? "00000001" : "00000000";
    }

    @Test
    @DisplayName("보호자가 발급하면 무작위 QR 토큰과 8자리 숫자 코드를 받는다")
    void issue() throws Exception {
        var res = primary("박지은").post("/api/access-codes", "").andExpect(status().isCreated());
        String token = read(res, "$.qrToken"), code = read(res, "$.code8");
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
        assertTrue(code.matches("\\d{8}"), code);
    }

    @Test
    @DisplayName("발급 응답에는 QR 토큰·코드·발급 시각만 있고 이름·건강정보는 없다")
    void noHealthInfo() throws Exception {
        judge().post("/api/access-codes", "")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.qrToken").isNotEmpty())
                .andExpect(jsonPath("$.code8").isNotEmpty())
                .andExpect(jsonPath("$.issuedAt").isNotEmpty());
    }

    @Test
    @DisplayName("발급한 QR·코드는 다시 볼 수 있고, 아직 없으면 404다")
    void current() throws Exception {
        TestUser u = primary("박지은");
        u.get("/api/access-codes").andExpect(status().isNotFound());
        String token = read(u.post("/api/access-codes", ""), "$.qrToken");
        u.get("/api/access-codes").andExpect(jsonPath("$.qrToken").value(token));
    }

    @Test
    @DisplayName("다시 발급하면 이전 QR과 8자리 코드는 더 이상 쓸 수 없다")
    void reissueRevokes() throws Exception {
        TestUser u = primary("박지은");
        var first = u.post("/api/access-codes", "");
        String oldToken = read(first, "$.qrToken"), oldCode = read(first, "$.code8");
        String newToken = read(u.post("/api/access-codes", ""), "$.qrToken");
        assertNotEquals(oldToken, newToken);

        anonymous().post("/api/public/access/" + oldToken + "/emergency", "").andExpect(status().isNotFound());
        anonymous().post(RESOLVE, byCode(oldCode)).andExpect(status().isNotFound());
        anonymous().post(RESOLVE, byToken(newToken)).andExpect(status().isOk());
        u.get("/api/access-codes").andExpect(jsonPath("$.qrToken").value(newToken));
    }

    @Test
    @DisplayName("연결된 일반 보호자도 발급할 수 있다")
    void memberCanIssue() throws Exception {
        member(primary("박지은"), "박민수").post("/api/access-codes", "").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("보호대상자가 없으면 발급할 수 없다")
    void needsRecipient() throws Exception {
        guardian("박지은").post("/api/access-codes", "").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("QR을 찍을 수 없으면 8자리 코드를 입력해 같은 대상으로 들어간다 (공백·하이픈 허용)")
    void resolveByCode() throws Exception {
        var res = primary("박지은").post("/api/access-codes", "");
        String token = read(res, "$.qrToken"), code = read(res, "$.code8");
        anonymous().post(RESOLVE, byCode(code.substring(0, 4) + " - " + code.substring(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.demo").value(false));
    }

    @Test
    @DisplayName("QR 토큰을 확인하면 그대로 돌려주고, 없는 토큰은 404다")
    void resolveByToken() throws Exception {
        String token = qrToken(primary("박지은"));
        anonymous().post(RESOLVE, byToken(token)).andExpect(jsonPath("$.token").value(token));
        anonymous().post(RESOLVE, byToken("nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("사용할 수 없는 QR이에요. 보호자에게 새 카드를 받아 주세요"));
    }

    @Test
    @DisplayName("8자리 숫자가 아니면 400이고 틀린 횟수로 세지 않는다")
    void malformedCode() throws Exception {
        TestUser u = anonymous();
        u.post(RESOLVE, byCode("1234")).andExpect(status().isBadRequest());
        u.post(RESOLVE, "{}").andExpect(status().isBadRequest());
        assertEquals(0L, count("code_attempts where ip_hash = ?", ips.hash(u.ip())));
    }

    @Test
    @DisplayName("틀린 코드는 404이고, 같은 IP에서 1시간에 10번 틀리면 맞는 코드도 막힌다")
    void bruteForceBlocked() throws Exception {
        String code = read(primary("박지은").post("/api/access-codes", ""), "$.code8");
        TestUser attacker = anonymous();
        for (int i = 0; i < 10; i++) {
            attacker.post(RESOLVE, byCode(wrongCode(code))).andExpect(status().isNotFound());
        }
        attacker.post(RESOLVE, byCode(code)).andExpect(status().isTooManyRequests());
        anonymous().post(RESOLVE, byCode(code)).andExpect(status().isOk()); // 다른 IP는 그대로
    }

    @Test
    @DisplayName("1시간이 지난 틀린 시도는 세지 않는다")
    void attemptsExpire() throws Exception {
        String code = read(primary("박지은").post("/api/access-codes", ""), "$.code8");
        TestUser u = anonymous();
        db.sql("insert into code_attempts (ip_hash, created_at) select ?, now() - interval '61 minutes' from generate_series(1, 10)")
                .param(ips.hash(u.ip())).update();
        u.post(RESOLVE, byCode(code)).andExpect(status().isOk());
    }
}
