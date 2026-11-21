package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("가입·로그인 (모의 문자 인증)")
class AuthTest extends ApiTest {

    static String verify(String phone, String code) {
        return "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code);
    }

    static String signup(String name, String birthDate) {
        return "{\"name\":\"%s\",\"birthDate\":\"%s\"}".formatted(name, birthDate);
    }

    @Test
    @DisplayName("인증 요청에는 모의 인증번호 안내가 온다")
    void otpRequestIsMock() throws Exception {
        anonymous().post("/api/auth/otp/request", "{\"phone\":\"%s\"}".formatted(uniquePhone()))
                .andExpect(jsonPath("$.mock").value(true))
                .andExpect(jsonPath("$.hint").value("000000"));
    }

    @Test
    @DisplayName("처음 쓰는 번호는 인증 후 가입하면 바로 로그인된다")
    void firstSignup() throws Exception {
        TestUser u = anonymous();
        u.post("/api/auth/otp/verify", verify(uniquePhone(), "000000")).andExpect(jsonPath("$.registered").value(false));
        u.get("/api/auth/me").andExpect(status().isUnauthorized());
        u.post("/api/auth/signup", signup("박지은", "1974-04-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("박지은"))
                .andExpect(jsonPath("$.birthDate").value("1974-04-12"))
                .andExpect(jsonPath("$.recipientId").doesNotExist());
        u.get("/api/auth/me").andExpect(jsonPath("$.name").value("박지은"));
    }

    @Test
    @DisplayName("하이픈을 넣어 입력해도 번호는 숫자만 저장된다")
    void phoneNormalized() throws Exception {
        String p = uniquePhone();
        String dashed = p.substring(0, 3) + "-" + p.substring(3, 7) + "-" + p.substring(7);
        anonymous().signup(dashed, "박지은").get("/api/auth/me").andExpect(jsonPath("$.phone").value(p));
    }

    @Test
    @DisplayName("가입된 번호로 인증하면 가입 없이 바로 로그인된다")
    void returningLogin() throws Exception {
        String p = uniquePhone();
        anonymous().signup(p, "박지은");
        TestUser again = anonymous();
        again.post("/api/auth/otp/verify", verify(p, "000000")).andExpect(jsonPath("$.registered").value(true));
        again.get("/api/auth/me").andExpect(jsonPath("$.name").value("박지은"));
    }

    @Test
    @DisplayName("인증번호가 틀리면 400이고 로그인되지 않는다")
    void wrongCode() throws Exception {
        String p = uniquePhone();
        anonymous().signup(p, "박지은");
        TestUser u = anonymous();
        u.post("/api/auth/otp/verify", verify(p, "123456"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("인증번호가 맞지 않아요"));
        u.get("/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("휴대폰 번호 형식이 아니면 400이다")
    void invalidPhone() throws Exception {
        for (String bad : new String[]{"02-123-4567", "0101234", "abc", ""}) {
            anonymous().post("/api/auth/otp/verify", verify(bad, "000000")).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("전화번호 인증 없이 가입하면 401이다")
    void signupWithoutOtp() throws Exception {
        anonymous().post("/api/auth/signup", signup("박지은", "1974-04-12"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("전화번호 인증을 먼저 해 주세요"));
    }

    @Test
    @DisplayName("이름이 비었거나 생년월일이 없거나 미래면 400이다")
    void invalidSignup() throws Exception {
        TestUser u = anonymous();
        u.post("/api/auth/otp/verify", verify(uniquePhone(), "000000"));
        u.post("/api/auth/signup", signup("", "1974-04-12")).andExpect(status().isBadRequest());
        u.post("/api/auth/signup", "{\"name\":\"박지은\"}").andExpect(status().isBadRequest());
        u.post("/api/auth/signup", signup("박지은", LocalDate.now().plusDays(1).toString())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("같은 번호로 두 번 가입하면 두 번째는 409다")
    void duplicateSignup() throws Exception {
        String p = uniquePhone();
        TestUser a = anonymous(), b = anonymous();
        a.post("/api/auth/otp/verify", verify(p, "000000"));
        b.post("/api/auth/otp/verify", verify(p, "000000"));
        a.post("/api/auth/signup", signup("박지은", "1974-04-12")).andExpect(status().isOk());
        b.post("/api/auth/signup", signup("박지은", "1974-04-12")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("인증할 때마다 세션이 새로 바뀐다 (세션 고정 공격 방지)")
    void sessionRotates() throws Exception {
        TestUser u = anonymous();
        String before = u.sessionId();
        u.post("/api/auth/otp/verify", verify(uniquePhone(), "000000"));
        assertNotEquals(before, u.sessionId());
    }

    @Test
    @DisplayName("로그인한 채 다른 번호로 인증하면 이전 로그인이 풀린다")
    void reverifyDropsPreviousLogin() throws Exception {
        TestUser u = guardian("박지은");
        u.post("/api/auth/otp/verify", verify(uniquePhone(), "000000")).andExpect(jsonPath("$.registered").value(false));
        u.get("/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃하면 보호자 API가 다시 401이다")
    void logout() throws Exception {
        TestUser u = primary("박지은");
        u.get("/api/guardians").andExpect(status().isOk());
        u.post("/api/auth/logout", "").andExpect(status().isNoContent());
        u.get("/api/guardians").andExpect(status().isUnauthorized());
    }
}
