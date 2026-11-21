package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("보호자 연결 (초대코드)")
class GuardianLinkTest extends ApiTest {

    static String redeem(String code, boolean agreed) {
        return "{\"code\":\"%s\",\"agreed\":%s}".formatted(code, agreed);
    }

    String issue(TestUser primary) throws Exception {
        return read(primary.post("/api/guardians/invite-codes", "").andExpect(status().isCreated()), "$.code");
    }

    @Test
    @DisplayName("핵심 보호자가 코드를 만들고 다른 보호자가 동의하며 입력하면 연결된다")
    void link() throws Exception {
        TestUser jieun = primary("박지은");
        String code = issue(jieun);
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem(code, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("박지은"))
                .andExpect(jsonPath("$[0].role").value("primary"))
                .andExpect(jsonPath("$[1].name").value("박민수"))
                .andExpect(jsonPath("$[1].role").value("member"));
        jieun.get("/api/guardians").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("초대코드는 헷갈리는 글자(0·O·1·I) 없는 8자리이고 24시간 뒤 만료된다")
    void codeFormat() throws Exception {
        var res = primary("박지은").post("/api/guardians/invite-codes", "");
        String code = read(res, "$.code");
        OffsetDateTime expiresAt = OffsetDateTime.parse(read(res, "$.expiresAt"));
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{8}"), code);
        long hours = Duration.between(OffsetDateTime.now(), expiresAt).toMinutes();
        assertTrue(hours > 23 * 60 && hours <= 24 * 60, "만료까지 " + hours + "분");
    }

    @Test
    @DisplayName("연결에 동의하지 않으면 연결되지 않는다")
    void needsAgreement() throws Exception {
        TestUser jieun = primary("박지은");
        String code = issue(jieun);
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem(code, false))
                .andExpect(status().isBadRequest());
        jieun.get("/api/guardians").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("소문자나 앞뒤 공백을 섞어 입력해도 연결된다")
    void lenientInput() throws Exception {
        String code = issue(primary("박지은"));
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem("  " + code.toLowerCase() + " ", true))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("초대코드는 한 번만 쓸 수 있다")
    void singleUse() throws Exception {
        String code = issue(primary("박지은"));
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem(code, true)).andExpect(status().isOk());
        guardian("제삼자").post("/api/guardians/invite-codes/redeem", redeem(code, true))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("24시간이 지난 초대코드는 쓸 수 없다")
    void expired() throws Exception {
        String code = issue(primary("박지은"));
        db.sql("update guardian_invites set expires_at = now() - interval '1 second' where code = ?").param(code).update();
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem(code, true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("초대코드가 없거나 이미 쓰였거나 만료됐어요"));
    }

    @Test
    @DisplayName("없는 코드는 404다")
    void unknown() throws Exception {
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem("ZZZZZZZZ", true))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("일반 보호자는 초대코드를 만들 수 없다")
    void memberCannotIssue() throws Exception {
        member(primary("박지은"), "박민수").post("/api/guardians/invite-codes", "")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 다른 보호대상자와 연결된 보호자는 초대코드를 쓸 수 없다")
    void alreadyLinked() throws Exception {
        String code = issue(primary("박지은"));
        primary("다른집").post("/api/guardians/invite-codes/redeem", redeem(code, true))
                .andExpect(status().isConflict());
        guardian("박민수").post("/api/guardians/invite-codes/redeem", redeem(code, true))
                .andExpect(status().isOk()); // 실패한 시도로 코드가 소모되지 않는다
    }

    @Test
    @DisplayName("목록에는 이름·역할·연결 시각만 있고 전화번호는 없다")
    void listHidesPhone() throws Exception {
        primary("박지은").get("/api/guardians")
                .andExpect(jsonPath("$[0].name").value("박지은"))
                .andExpect(jsonPath("$[0].joinedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].phone").doesNotExist());
    }

    @Test
    @DisplayName("보호대상자가 없으면 초대코드도 목록도 404다")
    void needsRecipient() throws Exception {
        TestUser u = guardian("박지은");
        u.post("/api/guardians/invite-codes", "").andExpect(status().isNotFound());
        u.get("/api/guardians").andExpect(status().isNotFound());
    }
}
