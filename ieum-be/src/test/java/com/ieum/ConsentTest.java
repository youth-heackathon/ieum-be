package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("항목별 동의")
class ConsentTest extends ApiTest {

    @Test
    @DisplayName("항목별로 저장하고 동의 여부·시각·동의한 사람을 남긴다")
    void savesPerItem() throws Exception {
        TestUser u = primary("박지은");
        String me = read(u.get("/api/auth/me"), "$.id");
        u.post("/api/consents", "{\"items\":{\"PERSONAL\":true,\"HEALTH\":true,\"OVERSEAS_AI\":false}}")
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.item=='PERSONAL')].agreed").value(true))
                .andExpect(jsonPath("$.items[?(@.item=='OVERSEAS_AI')].agreed").value(false))
                .andExpect(jsonPath("$.items[0].agreedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].agreedBy").value(me));
        u.get("/api/consents").andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    @DisplayName("다섯 항목을 모두 받는다: 개인정보·건강정보·긴급조회 공개·상세조회 제공·해외 AI 전송")
    void allFiveItems() throws Exception {
        primary("박지은").post("/api/consents",
                        "{\"items\":{\"PERSONAL\":true,\"HEALTH\":true,\"EMERGENCY\":true,\"DETAIL\":true,\"OVERSEAS_AI\":true}}")
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    @DisplayName("본인 동의·후견인 여부를 저장한다 (처음엔 둘 다 아니오)")
    void selfConsentAndGuardian() throws Exception {
        TestUser u = primary("박지은");
        u.get("/api/consents")
                .andExpect(jsonPath("$.selfConsent").value(false))
                .andExpect(jsonPath("$.guardianOfRecord").value(false))
                .andExpect(jsonPath("$.items.length()").value(0));
        u.post("/api/consents", "{\"selfConsent\":true,\"guardianOfRecord\":true,\"items\":{}}")
                .andExpect(jsonPath("$.selfConsent").value(true))
                .andExpect(jsonPath("$.guardianOfRecord").value(true));
    }

    @Test
    @DisplayName("보낸 것만 바뀌고 안 보낸 항목·본인 동의는 그대로다")
    void partialUpdate() throws Exception {
        TestUser u = primary("박지은");
        u.post("/api/consents", "{\"selfConsent\":true,\"items\":{\"PERSONAL\":true,\"OVERSEAS_AI\":false}}");
        u.post("/api/consents", "{\"items\":{\"OVERSEAS_AI\":true}}")
                .andExpect(jsonPath("$.selfConsent").value(true))
                .andExpect(jsonPath("$.items[?(@.item=='PERSONAL')].agreed").value(true))
                .andExpect(jsonPath("$.items[?(@.item=='OVERSEAS_AI')].agreed").value(true));
    }

    @Test
    @DisplayName("연결된 다른 보호자가 바꾸면 동의한 사람이 그 보호자로 남는다")
    void otherGuardianChanges() throws Exception {
        TestUser jieun = primary("박지은");
        TestUser minsu = member(jieun, "박민수");
        String minsuId = read(minsu.get("/api/auth/me"), "$.id");
        jieun.post("/api/consents", "{\"items\":{\"DETAIL\":false}}");
        minsu.post("/api/consents", "{\"items\":{\"DETAIL\":true}}");
        jieun.get("/api/consents")
                .andExpect(jsonPath("$.items[?(@.item=='DETAIL')].agreed").value(true))
                .andExpect(jsonPath("$.items[?(@.item=='DETAIL')].agreedBy").value(minsuId));
    }

    @Test
    @DisplayName("없는 항목, 빈 값, items 누락은 400이다")
    void invalid() throws Exception {
        TestUser u = primary("박지은");
        u.post("/api/consents", "{\"items\":{\"NOPE\":true}}").andExpect(status().isBadRequest());
        u.post("/api/consents", "{\"items\":{\"PERSONAL\":null}}").andExpect(status().isBadRequest());
        u.post("/api/consents", "{\"selfConsent\":true}").andExpect(status().isBadRequest());
        u.get("/api/consents").andExpect(jsonPath("$.selfConsent").value(false));
    }

    @Test
    @DisplayName("보호대상자가 없으면 동의를 저장·조회할 수 없다 (404)")
    void needsRecipient() throws Exception {
        TestUser u = guardian("박지은");
        u.get("/api/consents").andExpect(status().isNotFound());
        u.post("/api/consents", "{\"items\":{\"PERSONAL\":true}}").andExpect(status().isNotFound());
    }
}
