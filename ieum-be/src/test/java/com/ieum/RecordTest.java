package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("기록 관리 (보호자)")
class RecordTest extends ApiTest {

    String recordId(TestUser g, String category) throws Exception {
        List<String> ids = read(g.get("/api/records"), "$[?(@.category=='" + category + "')].id");
        return ids.get(0);
    }

    String emergency(TestUser g) throws Exception {
        return "/api/public/access/" + qrToken(g) + "/emergency";
    }

    @Test
    @DisplayName("기록 목록에는 정리문·원문 인용·검증 결과·마지막 확인일이 있다")
    void list() throws Exception {
        judge().get("/api/records")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].category").value("알러지"))
                .andExpect(jsonPath("$[0].quote").value("페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요"))
                .andExpect(jsonPath("$[0].verifyStatus").value("pass"))
                .andExpect(jsonPath("$[0].emergencyVisible").value(true))
                .andExpect(jsonPath("$[0].lastCheckedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].stale").value(false));
    }

    @Test
    @DisplayName("고치면 내용이 바뀌고 마지막 확인일이 오늘이 된다")
    void edit() throws Exception {
        TestUser g = judge();
        String id = recordId(g, "진정법");
        db.sql("update care_records set last_checked_at = now() - interval '40 days' where id = ?").param(UUID.fromString(id)).update();
        g.patch("/api/records/" + id, "{\"summary\":\"손을 잡고 고향의 봄을 불러 드려요\"}")
                .andExpect(jsonPath("$.summary").value("손을 잡고 고향의 봄을 불러 드려요"))
                .andExpect(jsonPath("$.emergencyVisible").value(false))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    @DisplayName("긴급조회 노출을 켜면 1단계에 보이고, 끄면 사라진다")
    void toggleEmergency() throws Exception {
        TestUser g = judge();
        String calm = recordId(g, "진정법"), allergy = recordId(g, "알러지");
        g.patch("/api/records/" + calm, "{\"emergencyVisible\":true}");
        g.patch("/api/records/" + allergy, "{\"emergencyVisible\":false}");
        anonymous().post(emergency(g), "")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].category").value("진정법"));
    }

    @Test
    @DisplayName("빈 내용으로 고치면 400이고 그대로 남는다")
    void blankSummary() throws Exception {
        TestUser g = judge();
        String id = recordId(g, "알러지");
        g.patch("/api/records/" + id, "{\"summary\":\"  \"}").andExpect(status().isBadRequest());
        g.get("/api/records").andExpect(jsonPath("$[0].summary").value("페니실린 알레르기가 있어요"));
    }

    @Test
    @DisplayName("마지막 확인이 30일 지나면 배지가 붙고, 다시 확인하면 사라진다")
    void staleAndCheck() throws Exception {
        TestUser g = judge();
        String id = recordId(g, "특이사항");
        db.sql("update care_records set last_checked_at = now() - interval '31 days' where id = ?").param(UUID.fromString(id)).update();
        g.get("/api/records").andExpect(jsonPath("$[?(@.category=='특이사항')].stale").value(true));
        g.post("/api/records/" + id + "/check", "")
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.summary").value("저녁 5시쯤 되면 집에 가야 한다며 나가려고 하세요"));
    }

    @Test
    @DisplayName("지우면 목록과 상세 정보에서 사라지고, 없는 기록은 404다")
    void delete() throws Exception {
        TestUser g = judge();
        String id = recordId(g, "진정법");
        g.delete("/api/records/" + id).andExpect(status().isNoContent());
        g.get("/api/records").andExpect(jsonPath("$.length()").value(2));
        g.delete("/api/records/" + id).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 가구의 기록은 고치거나 지울 수 없다")
    void otherHousehold() throws Exception {
        String id = recordId(judge(), "알러지");
        TestUser stranger = judge();
        stranger.patch("/api/records/" + id, "{\"summary\":\"바꿈\"}").andExpect(status().isNotFound());
        stranger.post("/api/records/" + id + "/check", "").andExpect(status().isNotFound());
        stranger.delete("/api/records/" + id).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("보호자 확인 전 기록(AI 정리 대기)은 여기서 고칠 수 없다")
    void draftNotEditable() throws Exception {
        TestUser g = judge();
        String id = db.sql("insert into care_records (recipient_id, category, summary, verify_status) values (?, '특이사항', '임시', 'review') returning id")
                .param(recipientOf(g)).query(UUID.class).single().toString();
        g.get("/api/records").andExpect(jsonPath("$.length()").value(4)); // 목록에는 보인다 (confirmedAt=null)
        g.patch("/api/records/" + id, "{\"summary\":\"확정인 척\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("보호대상자가 없으면 404다")
    void needsRecipient() throws Exception {
        guardian("박지은").get("/api/records").andExpect(status().isNotFound());
    }
}
