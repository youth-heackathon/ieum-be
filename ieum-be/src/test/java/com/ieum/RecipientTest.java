package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("보호대상자 등록")
class RecipientTest extends ApiTest {

    @Test
    @DisplayName("등록하면 등록한 보호자가 핵심 보호자가 된다")
    void creatorBecomesPrimary() throws Exception {
        TestUser u = guardian("박지은");
        String id = read(u.post("/api/recipients", "{\"name\":\"김순자\",\"birthDate\":\"1946-09-03\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("김순자"))
                .andExpect(jsonPath("$.birthDate").value("1946-09-03")), "$.id");
        u.get("/api/auth/me")
                .andExpect(jsonPath("$.recipientId").value(id))
                .andExpect(jsonPath("$.role").value("primary"));
    }

    @Test
    @DisplayName("생년월일 없이도 등록할 수 있다 (등록 항목 미정)")
    void birthDateOptional() throws Exception {
        guardian("박지은").post("/api/recipients", "{\"name\":\"김순자\"}").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("보호자 1명은 보호대상자 1명만 등록할 수 있다")
    void onlyOnePerGuardian() throws Exception {
        primary("박지은").post("/api/recipients", "{\"name\":\"아버지\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("이미 연결된 보호대상자가 있어요"));
    }

    @Test
    @DisplayName("초대로 연결된 보호자는 보호대상자를 따로 등록할 수 없다")
    void memberCannotCreate() throws Exception {
        member(primary("박지은"), "박민수").post("/api/recipients", "{\"name\":\"아버지\"}")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("이름이 비었거나 생년월일이 미래면 400이다")
    void invalid() throws Exception {
        TestUser u = guardian("박지은");
        u.post("/api/recipients", "{\"name\":\" \"}").andExpect(status().isBadRequest());
        u.post("/api/recipients", "{\"name\":\"김순자\",\"birthDate\":\"%s\"}".formatted(LocalDate.now().plusDays(1)))
                .andExpect(status().isBadRequest());
        u.get("/api/auth/me").andExpect(jsonPath("$.recipientId").doesNotExist());
    }
}
