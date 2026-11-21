package com.ieum;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 브라우저처럼 세션을 들고 다니며 API를 부르는 테스트 사용자. */
class TestUser {

    private final MockMvc mvc;
    private MockHttpSession session = new MockHttpSession();

    TestUser(MockMvc mvc) {
        this.mvc = mvc;
    }

    ResultActions call(MockHttpServletRequestBuilder req) throws Exception {
        ResultActions result = mvc.perform(req.session(session).contentType(APPLICATION_JSON));
        var s = result.andReturn().getRequest().getSession(false); // 로그인하면 세션이 바뀐다
        if (s != null) session = (MockHttpSession) s;
        return result;
    }

    String sessionId() {
        return session.getId();
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
