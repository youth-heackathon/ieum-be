package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("긴급조회 (의료진)")
class EmergencyTest extends ApiTest {

    static String url(String token) {
        return "/api/public/access/" + token + "/emergency";
    }

    void seedRecord(UUID recipientId, String summary, boolean emergencyVisible, boolean confirmed) {
        db.sql("""
                        insert into care_records (recipient_id, category, summary, quote_text, verify_status, emergency_visible, confirmed_at)
                        values (?, '특이사항', ?, ?, 'pass', ?, case when ? then now() end)""")
                .params(recipientId, summary, summary, emergencyVisible, confirmed).update();
    }

    @Test
    @DisplayName("로그인 없이 조회하기만 누르면 보호자가 긴급조회용으로 고른 필수정보가 바로 보인다")
    void showsChosenInfo() throws Exception {
        String token = qrToken(judge());
        anonymous().post(url(token), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].category").value("알러지"))
                .andExpect(jsonPath("$.items[0].summary").value("페니실린 알레르기가 있어요"))
                .andExpect(jsonPath("$.medications.length()").value(2))
                .andExpect(jsonPath("$.medications[0].name").value("와파린정 2mg"))
                .andExpect(jsonPath("$.medications[0].highRisk").value(true));
    }

    @Test
    @DisplayName("약은 종류만 보이고 용량·원문 인용은 긴급조회에 나오지 않는다")
    void kindOnly() throws Exception {
        anonymous().post(url(qrToken(judge())), "")
                .andExpect(jsonPath("$.medications[0].dose").doesNotExist())
                .andExpect(jsonPath("$.medications[0].quote").doesNotExist())
                .andExpect(jsonPath("$.items[0].quote").doesNotExist());
    }

    @Test
    @DisplayName("긴급조회에 고르지 않은 기록과 보호자 확인 전 기록은 보이지 않는다")
    void hidesUnchosenAndDrafts() throws Exception {
        TestUser g = judge();
        seedRecord(recipientOf(g), "확인 전 메모", true, false);
        seedRecord(recipientOf(g), "긴급조회에 안 고른 메모", false, true);
        anonymous().post(url(qrToken(g)), "")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].category").value("알러지"));
    }

    @Test
    @DisplayName("조회할 때마다 기기정보와 IP 해시(원래 IP 아님)를 기록하고 응답에 고지를 붙인다")
    void logsDevice() throws Exception {
        String token = qrToken(judge());
        TestUser medic = anonymous();
        medic.call(post(url(token)).header("User-Agent", "Mozilla/5.0 (Galaxy S24)"))
                .andExpect(jsonPath("$.logged").value(true))
                .andExpect(jsonPath("$.notice").value("조회하면 기기 정보가 기록되고 보호자에게 알림이 가요"));
        Map<String, Object> log = db.sql("""
                        select l.user_agent, l.ip_hash from access_logs l join access_codes a on a.id = l.access_code_id
                        where a.qr_token = ? and l.kind = 'emergency'""")
                .param(token).query().singleRow();
        assertEquals("Mozilla/5.0 (Galaxy S24)", log.get("user_agent"));
        assertEquals(ips.hash(medic.ip()), log.get("ip_hash"));
    }

    @Test
    @DisplayName("보호자는 열람 기록에서 종류·시각·기기정보를 본다 (최신순)")
    void guardianSeesLogs() throws Exception {
        TestUser g = judge();
        String token = qrToken(g);
        anonymous().call(post(url(token)).header("User-Agent", "첫 번째 기기"));
        anonymous().call(post(url(token)).header("User-Agent", "두 번째 기기"));
        g.get("/api/access-logs")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("emergency"))
                .andExpect(jsonPath("$[0].userAgent").value("두 번째 기기"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("조회가 일어나면 연결된 보호자 전원에게 알림이 간다")
    void notifiesAllGuardians() throws Exception {
        TestUser jieun = primary("박지은");
        TestUser minsu = member(jieun, "박민수");
        anonymous().post(url(qrToken(jieun)), "");
        jieun.get("/api/notifications").andExpect(jsonPath("$[0].kind").value("emergency_view"));
        minsu.get("/api/notifications").andExpect(jsonPath("$[0].kind").value("emergency_view"));
    }

    @Test
    @DisplayName("긴급조회 공개에 동의하지 않았으면 정보는 빼고 기록·알림만 남긴다")
    void withoutConsent() throws Exception {
        TestUser g = primary("박지은");
        g.post("/api/consents", "{\"selfConsent\":true,\"items\":{\"EMERGENCY\":false}}");
        seedRecord(recipientOf(g), "페니실린 알레르기", true, true);
        anonymous().post(url(qrToken(g)), "")
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.medications.length()").value(0))
                .andExpect(jsonPath("$.logged").value(true));
        g.get("/api/access-logs").andExpect(jsonPath("$.length()").value(1));
        g.get("/api/notifications").andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("본인 동의도 후견인도 없으면 공개 동의가 있어도 보여 주지 않는다")
    void sensitiveGate() throws Exception {
        TestUser g = judge();
        String token = qrToken(g);
        g.post("/api/consents", "{\"selfConsent\":false,\"items\":{}}");
        anonymous().post(url(token), "").andExpect(jsonPath("$.allowed").value(false));
        g.post("/api/consents", "{\"guardianOfRecord\":true,\"items\":{}}");
        anonymous().post(url(token), "").andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    @DisplayName("없는 QR이나 재발급으로 무효가 된 QR은 404다")
    void invalidQr() throws Exception {
        TestUser g = primary("박지은");
        String old = qrToken(g);
        g.post("/api/access-codes", "");
        anonymous().post(url("nope"), "").andExpect(status().isNotFound());
        anonymous().post(url(old), "").andExpect(status().isNotFound());
        g.get("/api/notifications").andExpect(jsonPath("$.length()").value(0));
    }
}
