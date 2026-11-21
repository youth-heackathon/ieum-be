package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("보호자 홈 · 체험 노트북 폴링")
class HomeTest extends ApiTest {

    @Test
    @DisplayName("보호대상자를 등록하기 전에는 보호대상자가 비어 있고 목록도 비어 있다")
    void beforeRecipient() throws Exception {
        guardian("박지은").get("/api/home")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").doesNotExist())
                .andExpect(jsonPath("$.hasAccessCode").value(false))
                .andExpect(jsonPath("$.pendingDetailRequests").isEmpty())
                .andExpect(jsonPath("$.demo").doesNotExist());
    }

    @Test
    @DisplayName("체험 홈에는 보호대상자·역할·QR 발급 여부·체험 단계가 있다")
    void demoHome() throws Exception {
        judge().get("/api/home")
                .andExpect(jsonPath("$.recipient.name").value("김순자"))
                .andExpect(jsonPath("$.role").value("primary"))
                .andExpect(jsonPath("$.hasAccessCode").value(true))
                .andExpect(jsonPath("$.demo.step").value(1))
                .andExpect(jsonPath("$.unreadNotifications").value(0));
    }

    @Test
    @DisplayName("노트북은 홈 하나만 폴링해도 단계가 바뀐 것을 안다")
    void followsStep() throws Exception {
        TestUser laptop = judge();
        laptop.patch("/api/demo/sessions/me", "{\"step\":4}");
        laptop.get("/api/home").andExpect(jsonPath("$.demo.step").value(4));
    }

    @Test
    @DisplayName("의료진이 긴급조회하면 안 읽은 알림과 최근 열람에 바로 뜬다")
    void emergencyShowsUp() throws Exception {
        TestUser g = judge();
        anonymous().post("/api/public/access/" + qrToken(g) + "/emergency", "");
        g.get("/api/home")
                .andExpect(jsonPath("$.unreadNotifications").value(1))
                .andExpect(jsonPath("$.recentAccess[0].kind").value("emergency"));
    }

    @Test
    @DisplayName("간병인이 상세조회를 요청하면 대기 목록에 남은 시간과 함께 뜨고, 동의하면 빠진다")
    void pendingShowsUp() throws Exception {
        TestUser g = judge();
        String id = read(anonymous().post("/api/public/access/" + qrToken(g) + "/detail-requests", ""), "$.id");
        g.get("/api/home")
                .andExpect(jsonPath("$.pendingDetailRequests.length()").value(1))
                .andExpect(jsonPath("$.pendingDetailRequests[0].remainingSeconds", allOf(greaterThan(590), lessThanOrEqualTo(600))));
        g.post("/api/detail-requests/" + id + "/approve", "");
        g.get("/api/home").andExpect(jsonPath("$.pendingDetailRequests").isEmpty());
    }

    @Test
    @DisplayName("마지막 확인이 30일 지난 기록이 뜬다")
    void staleShowsUp() throws Exception {
        TestUser g = judge();
        db.sql("update care_records set last_checked_at = now() - interval '31 days' where recipient_id = ? and category = '진정법'")
                .param(recipientOf(g)).update();
        g.get("/api/home")
                .andExpect(jsonPath("$.staleRecords.length()").value(1))
                .andExpect(jsonPath("$.staleRecords[0].category").value("진정법"));
    }

    @Test
    @DisplayName("실제 보호자 홈에는 체험 정보가 없고, QR을 만들면 발급 여부가 바뀐다")
    void realGuardian() throws Exception {
        TestUser g = primary("박지은");
        g.get("/api/home")
                .andExpect(jsonPath("$.recipient.name").value("어머니"))
                .andExpect(jsonPath("$.demo").doesNotExist())
                .andExpect(jsonPath("$.hasAccessCode").value(false));
        g.post("/api/access-codes", "");
        g.get("/api/home").andExpect(jsonPath("$.hasAccessCode").value(true));
    }
}
