package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("상세조회 (인계받을 간병인)")
class DetailRequestTest extends ApiTest {

    static String requestUrl(String token) {
        return "/api/public/access/" + token + "/detail-requests";
    }

    static String statusUrl(String id) {
        return "/api/public/detail-requests/" + id;
    }

    /** 체험 가구(동의 완료·기록·약 있음)에 간병인이 상세조회를 요청한 상태. */
    record Requested(TestUser guardian, TestUser caregiver, String id) {}

    Requested requested() throws Exception {
        TestUser g = judge(), c = anonymous();
        String id = read(c.post(requestUrl(qrToken(g)), "").andExpect(status().isCreated()), "$.id");
        return new Requested(g, c, id);
    }

    String approve(Requested r) throws Exception {
        r.guardian().post("/api/detail-requests/" + r.id() + "/approve", "").andExpect(status().isOk());
        return read(r.caregiver().get(statusUrl(r.id())), "$.viewToken");
    }

    void expire(String requestId) {
        db.sql("update detail_requests set deadline_at = now() - interval '1 second' where id = ?")
                .param(UUID.fromString(requestId)).update();
    }

    @Test
    @DisplayName("간병인이 요청하면 10분 마감의 대기 상태가 되고 보호자에게 알림이 간다")
    void request() throws Exception {
        TestUser g = judge();
        var res = anonymous().post(requestUrl(qrToken(g)), "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.viewToken").doesNotExist());
        long seconds = Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(read(res, "$.deadlineAt"))).toSeconds();
        assertTrue(seconds > 590 && seconds <= 600, "마감까지 " + seconds + "초");
        g.get("/api/notifications")
                .andExpect(jsonPath("$[0].kind").value("detail_request"))
                .andExpect(jsonPath("$[0].refId").value((String) read(res, "$.id")));
    }

    @Test
    @DisplayName("보호자 대기 목록에 남은 시간·기기정보·요청자 번호가 보인다")
    void pendingList() throws Exception {
        TestUser g = judge();
        anonymous().call(post(requestUrl(qrToken(g)))
                .content("{\"requesterPhone\":\"010-2222-3333\"}").header("User-Agent", "iPhone 16"));
        g.get("/api/detail-requests")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].remainingSeconds", allOf(greaterThan(590), lessThanOrEqualTo(600))))
                .andExpect(jsonPath("$[0].userAgent").value("iPhone 16"))
                .andExpect(jsonPath("$[0].requesterPhone").value("01022223333"));
    }

    @Test
    @DisplayName("보호자가 10분 안에 동의하면 요청자 화면이 확인됨으로 바뀌고 60분짜리 열람 토큰을 받는다")
    void approve() throws Exception {
        Requested r = requested();
        var res = r.guardian().post("/api/detail-requests/" + r.id() + "/approve", "")
                .andExpect(jsonPath("$.status").value("approved"));
        long minutes = Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(read(res, "$.viewExpiresAt"))).toMinutes();
        assertTrue(minutes >= 59 && minutes <= 60, "열람 " + minutes + "분");
        r.caregiver().get(statusUrl(r.id()))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.viewToken").isNotEmpty());
        r.guardian().get("/api/detail-requests").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("연결된 일반 보호자도 동의할 수 있다 (보호자 한 명이 쓰러져도)")
    void memberApproves() throws Exception {
        TestUser jieun = primary("박지은");
        jieun.post("/api/consents", "{\"selfConsent\":true,\"items\":{\"DETAIL\":true}}");
        TestUser minsu = member(jieun, "박민수");
        String id = read(anonymous().post(requestUrl(qrToken(jieun)), ""), "$.id");
        minsu.post("/api/detail-requests/" + id + "/approve", "").andExpect(jsonPath("$.status").value("approved"));
    }

    @Test
    @DisplayName("거절하면 요청자는 거절 상태를 보고 열람 토큰은 없다")
    void reject() throws Exception {
        Requested r = requested();
        r.guardian().post("/api/detail-requests/" + r.id() + "/reject", "").andExpect(jsonPath("$.status").value("rejected"));
        r.caregiver().get(statusUrl(r.id()))
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.viewToken").doesNotExist());
    }

    @Test
    @DisplayName("10분이 지나면 만료되어 동의할 수 없고 대기 목록에서도 빠진다")
    void expires() throws Exception {
        Requested r = requested();
        expire(r.id());
        r.caregiver().get(statusUrl(r.id())).andExpect(jsonPath("$.status").value("expired"));
        r.guardian().post("/api/detail-requests/" + r.id() + "/approve", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("이미 처리됐거나 10분이 지난 요청이에요"));
        r.guardian().get("/api/detail-requests").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("만료된 뒤에도 긴급조회 정보는 볼 수 있고 다시 요청할 수 있다")
    void afterExpiry() throws Exception {
        Requested r = requested();
        expire(r.id());
        String token = qrToken(r.guardian());
        r.caregiver().post("/api/public/access/" + token + "/emergency", "").andExpect(jsonPath("$.allowed").value(true));
        r.caregiver().post(requestUrl(token), "").andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    @DisplayName("이미 처리한 요청은 다시 동의·거절할 수 없다")
    void decideOnce() throws Exception {
        Requested r = requested();
        approve(r);
        r.guardian().post("/api/detail-requests/" + r.id() + "/approve", "").andExpect(status().isConflict());
        r.guardian().post("/api/detail-requests/" + r.id() + "/reject", "").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("다른 가구 보호자는 이 요청에 동의·거절할 수 없다")
    void otherHousehold() throws Exception {
        Requested r = requested();
        TestUser stranger = judge();
        stranger.post("/api/detail-requests/" + r.id() + "/approve", "").andExpect(status().isConflict());
        stranger.post("/api/detail-requests/" + r.id() + "/reject", "").andExpect(status().isConflict());
        r.caregiver().get(statusUrl(r.id())).andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    @DisplayName("상세조회 제공에 동의하지 않았으면 동의를 눌러도 열리지 않는다")
    void needsDetailConsent() throws Exception {
        Requested r = requested();
        r.guardian().post("/api/consents", "{\"items\":{\"DETAIL\":false}}");
        r.guardian().post("/api/detail-requests/" + r.id() + "/approve", "").andExpect(status().isConflict());
        r.caregiver().get(statusUrl(r.id())).andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    @DisplayName("상세 정보에는 확정된 기록 전부와 원문 인용, 약 용량·고위험 여부, 고정 문구, 마지막 확인일이 있다")
    void detailContent() throws Exception {
        Requested r = requested();
        r.caregiver().get("/api/public/detail/" + approve(r))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice").value("보호자가 적은 돌봄 방법이며 의료 지시가 아닙니다."))
                .andExpect(jsonPath("$.lastCheckedAt").isNotEmpty())
                .andExpect(jsonPath("$.viewExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.records.length()").value(3))
                .andExpect(jsonPath("$.records[?(@.category=='진정법')].quote")
                        .value("불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요"))
                .andExpect(jsonPath("$.medications[0].name").value("와파린정 2mg"))
                .andExpect(jsonPath("$.medications[0].dose").value("하루 1번 아침 식후 1알"))
                .andExpect(jsonPath("$.medications[0].highRisk").value(true))
                .andExpect(jsonPath("$.address").doesNotExist());
    }

    @Test
    @DisplayName("보호자 확인 전 기록은 상세 정보에도 나오지 않는다")
    void hidesDrafts() throws Exception {
        Requested r = requested();
        db.sql("insert into care_records (recipient_id, category, summary, verify_status) values (?, '특이사항', '임시', 'review')")
                .param(recipientOf(r.guardian())).update();
        r.caregiver().get("/api/public/detail/" + approve(r)).andExpect(jsonPath("$.records.length()").value(3));
    }

    @Test
    @DisplayName("상세 정보를 볼 때마다 열람 기록이 남는다")
    void viewLogged() throws Exception {
        Requested r = requested();
        String viewToken = approve(r);
        r.caregiver().get("/api/public/detail/" + viewToken);
        r.caregiver().get("/api/public/detail/" + viewToken);
        r.guardian().get("/api/access-logs")
                .andExpect(jsonPath("$[0].kind").value("detail_view"))
                .andExpect(jsonPath("$[?(@.kind=='detail_view')]", hasSize(2)))
                .andExpect(jsonPath("$[?(@.kind=='detail_request')]", hasSize(1)));
    }

    @Test
    @DisplayName("동의한 뒤 보호자가 상세조회 제공 동의를 거두면 바로 볼 수 없다")
    void consentWithdrawn() throws Exception {
        Requested r = requested();
        String viewToken = approve(r);
        r.guardian().post("/api/consents", "{\"items\":{\"DETAIL\":false}}");
        r.caregiver().get("/api/public/detail/" + viewToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("보호자가 상세 정보 제공에 동의하지 않았어요"));
    }

    @Test
    @DisplayName("열람 시간이 끝나면 볼 수 없고 요청자 화면에서도 토큰이 사라진다")
    void viewExpires() throws Exception {
        Requested r = requested();
        String viewToken = approve(r);
        db.sql("update detail_requests set view_expires_at = now() - interval '1 second' where id = ?")
                .param(UUID.fromString(r.id())).update();
        r.caregiver().get("/api/public/detail/" + viewToken).andExpect(status().isNotFound());
        r.caregiver().get(statusUrl(r.id())).andExpect(jsonPath("$.viewToken").doesNotExist());
    }

    @Test
    @DisplayName("없는 열람 주소·요청은 404다")
    void unknown() throws Exception {
        anonymous().get("/api/public/detail/nope").andExpect(status().isNotFound());
        anonymous().get(statusUrl(UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("요청자 번호는 선택이고, 받으면 숫자만 저장해 30일 뒤 파기일을 잡는다")
    void requesterPhone() throws Exception {
        String token = qrToken(judge());
        String id = read(anonymous().post(requestUrl(token), "{\"requesterPhone\":\"010-2222-3333\"}"), "$.id");
        var row = db.sql("select requester_phone, extract(day from phone_purge_at - now()) as days from detail_requests where id = ?")
                .param(UUID.fromString(id)).query().singleRow();
        assertEquals("01022223333", row.get("requester_phone"));
        assertEquals(29, ((Number) row.get("days")).intValue()); // 29일 23시간 59분…
        anonymous().post(requestUrl(token), "").andExpect(status().isCreated());
        anonymous().post(requestUrl(token), "{\"requesterPhone\":\"12345\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("같은 IP에서 1시간에 10번 넘게 요청하면 429다")
    void perIpLimit() throws Exception {
        String token = qrToken(judge());
        TestUser spammer = anonymous();
        for (int i = 0; i < 10; i++) spammer.post(requestUrl(token), "").andExpect(status().isCreated());
        spammer.post(requestUrl(token), "").andExpect(status().isTooManyRequests());
        anonymous().post(requestUrl(token), "").andExpect(status().isCreated());
    }
}
