package com.ieum;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 1단계 흐름 전체: 가입 → 보호대상자 등록 → 동의 → 초대코드 → 두 번째 보호자 연결 → 목록. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class GuardianFlowTest {

    @Autowired
    MockMvc mvc;

    /** 브라우저처럼 세션을 들고 다니는 사용자. */
    class User {
        MockHttpSession session = new MockHttpSession();

        ResultActions call(MockHttpServletRequestBuilder req) throws Exception {
            ResultActions result = mvc.perform(req.session(session).contentType(APPLICATION_JSON));
            var s = result.andReturn().getRequest().getSession(false);
            if (s != null) session = (MockHttpSession) s;
            return result;
        }

        ResultActions post_(String url, String json) throws Exception {
            return call(post(url).content(json));
        }

        ResultActions get_(String url) throws Exception {
            return call(get(url));
        }

        User signup(String phone, String name) throws Exception {
            post_("/api/auth/otp/verify", "{\"phone\":\"%s\",\"code\":\"000000\"}".formatted(phone))
                    .andExpect(jsonPath("$.registered").value(false));
            post_("/api/auth/signup", "{\"name\":\"%s\",\"birthDate\":\"1974-03-02\"}".formatted(name))
                    .andExpect(status().isOk());
            return this;
        }
    }

    @Test
    void phase1Flow() throws Exception {
        mvc.perform(get("/health")).andExpect(content().string("ok"));

        var anon = new User();
        anon.get_("/api/guardians").andExpect(status().isUnauthorized());
        anon.post_("/api/auth/otp/verify", "{\"phone\":\"010-1111-2222\",\"code\":\"123456\"}")
                .andExpect(status().isBadRequest());
        anon.post_("/api/auth/signup", "{\"name\":\"x\",\"birthDate\":\"1974-03-02\"}")
                .andExpect(status().isUnauthorized());

        // 핵심 보호자
        var jieun = new User().signup("010-1111-2222", "박지은");
        jieun.get_("/api/auth/me")
                .andExpect(jsonPath("$.phone").value("01011112222"))
                .andExpect(jsonPath("$.recipientId").doesNotExist());
        jieun.get_("/api/guardians").andExpect(status().isNotFound());

        jieun.post_("/api/recipients", "{\"name\":\"김순자\",\"birthDate\":\"1948-05-01\"}")
                .andExpect(status().isCreated());
        jieun.post_("/api/recipients", "{\"name\":\"또\"}").andExpect(status().isConflict());
        jieun.get_("/api/auth/me").andExpect(jsonPath("$.role").value("primary"));

        jieun.post_("/api/consents", "{\"selfConsent\":true,\"items\":{\"PERSONAL\":true,\"OVERSEAS_AI\":false}}")
                .andExpect(jsonPath("$.selfConsent").value(true))
                .andExpect(jsonPath("$.items.length()").value(2));
        jieun.post_("/api/consents", "{\"items\":{\"OVERSEAS_AI\":true}}")
                .andExpect(jsonPath("$.selfConsent").value(true)) // 안 보낸 값은 그대로
                .andExpect(jsonPath("$.items[?(@.item=='OVERSEAS_AI')].agreed").value(true));
        jieun.post_("/api/consents", "{\"items\":{\"NOPE\":true}}").andExpect(status().isBadRequest());

        String code = JsonPath.read(jieun.post_("/api/guardians/invite-codes", "")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.code");

        // 두 번째 보호자: 동의 없이는 연결 안 됨, 소문자로 입력해도 됨
        var minsu = new User().signup("010-3333-4444", "박민수");
        String redeem = "{\"code\":\"%s\",\"agreed\":%s}";
        minsu.post_("/api/guardians/invite-codes/redeem", redeem.formatted(code, false))
                .andExpect(status().isBadRequest());
        minsu.post_("/api/guardians/invite-codes/redeem", redeem.formatted(code.toLowerCase(), true))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("primary"))
                .andExpect(jsonPath("$[1].role").value("member"));
        minsu.post_("/api/guardians/invite-codes", "").andExpect(status().isForbidden());

        // 1회용
        new User().signup("010-5555-6666", "제삼자")
                .post_("/api/guardians/invite-codes/redeem", redeem.formatted(code, true))
                .andExpect(status().isNotFound());

        // 재로그인 → 가입된 번호로 인식, 로그아웃하면 막힘
        var again = new User();
        again.post_("/api/auth/otp/verify", "{\"phone\":\"01011112222\",\"code\":\"000000\"}")
                .andExpect(jsonPath("$.registered").value(true));
        again.get_("/api/guardians").andExpect(jsonPath("$.length()").value(2));
        again.post_("/api/auth/logout", "").andExpect(status().isNoContent());
        again.get_("/api/guardians").andExpect(status().isUnauthorized());
    }
}
