package com.ieum;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 브라우저처럼 세션 쿠키를 받아 두었다가 다음 요청에 실어 보내는 테스트 사용자. */
class TestUser {

    private static final AtomicInteger IPS = new AtomicInteger();

    private final MockMvc mvc;
    private final String ip; // 사용자마다 다른 IP → IP당 제한이 테스트끼리 섞이지 않는다
    private Cookie session;  // 서버가 준 SESSION 쿠키 (세션은 DB에 있다)

    TestUser(MockMvc mvc) {
        this.mvc = mvc;
        int n = IPS.incrementAndGet();
        this.ip = "10.%d.%d.%d".formatted(n >> 16 & 255, n >> 8 & 255, n & 255);
    }

    String ip() {
        return ip;
    }

    ResultActions call(MockHttpServletRequestBuilder req) throws Exception {
        if (session != null) req.cookie(session);
        ResultActions result = mvc.perform(req.contentType(APPLICATION_JSON).with(r -> { r.setRemoteAddr(ip); return r; }));
        Cookie set = result.andReturn().getResponse().getCookie("SESSION"); // 로그인·로그아웃하면 바뀐다
        if (set != null) session = set.getMaxAge() == 0 ? null : set;
        return result;
    }

    /** DB(spring_session.session_id)에 저장된 세션 id. 세션이 없으면 null. */
    String sessionId() {
        return session == null ? null : new String(Base64.getDecoder().decode(session.getValue()), UTF_8);
    }

    Cookie sessionCookie() {
        return session;
    }

    ResultActions get(String url) throws Exception {
        return call(MockMvcRequestBuilders.get(url));
    }

    ResultActions post(String url, String json) throws Exception {
        return call(MockMvcRequestBuilders.post(url).content(json));
    }

    ResultActions patch(String url, String json) throws Exception {
        return call(MockMvcRequestBuilders.patch(url).content(json));
    }

    TestUser signup(String phone, String name) throws Exception {
        post("/api/auth/otp/verify", "{\"phone\":\"%s\",\"code\":\"000000\"}".formatted(phone))
                .andExpect(jsonPath("$.registered").value(false));
        post("/api/auth/signup", "{\"name\":\"%s\",\"birthDate\":\"1974-03-02\"}".formatted(name))
                .andExpect(status().isOk());
        return this;
    }
}
