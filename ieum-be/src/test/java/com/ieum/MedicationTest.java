package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("약 관리 (보호자) · 1단계 표시 규칙")
class MedicationTest extends ApiTest {

    String medId(TestUser g, String name) throws Exception {
        List<String> ids = read(g.get("/api/medications"), "$[?(@.name=='" + name + "')].id");
        return ids.get(0);
    }

    String emergency(TestUser g) throws Exception {
        return "/api/public/access/" + qrToken(g) + "/emergency";
    }

    /** 간병인이 상세조회를 요청하고 보호자가 동의한 뒤의 상세 정보 주소. */
    String detailUrl(TestUser g) throws Exception {
        TestUser caregiver = anonymous();
        String id = read(caregiver.post("/api/public/access/" + qrToken(g) + "/detail-requests", ""), "$.id");
        g.post("/api/detail-requests/" + id + "/approve", "");
        return "/api/public/detail/" + read(caregiver.get("/api/public/detail-requests/" + id), "$.viewToken");
    }

    @Test
    @DisplayName("직접 등록한 약은 바로 확정되고, 긴급조회 노출은 켜짐·이름 숨김은 꺼짐이 기본이다")
    void create() throws Exception {
        primary("박지은").post("/api/medications", "{\"name\":\"아리셉트정 5mg\",\"dose\":\"1정\",\"timing\":\"자기 전\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("아리셉트정 5mg"))
                .andExpect(jsonPath("$.dose").value("1정"))
                .andExpect(jsonPath("$.timing").value("자기 전"))
                .andExpect(jsonPath("$.caution").doesNotExist())
                .andExpect(jsonPath("$.emergencyVisible").value(true))
                .andExpect(jsonPath("$.nameHidden").value(false))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());
    }

    @Test
    @DisplayName("용량 없이는 등록할 수 없다 (용량은 보호자가 꼭 확인)")
    void doseRequired() throws Exception {
        TestUser g = primary("박지은");
        g.post("/api/medications", "{\"name\":\"와파린정\"}").andExpect(status().isBadRequest());
        g.post("/api/medications", "{\"name\":\"와파린정\",\"dose\":\" \"}").andExpect(status().isBadRequest());
        g.get("/api/medications").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("주의 약이 목록 맨 앞에 온다")
    void cautionFirst() throws Exception {
        TestUser g = primary("박지은");
        g.post("/api/medications", "{\"name\":\"타이레놀\",\"dose\":\"1정\"}");
        g.post("/api/medications", "{\"name\":\"와파린정\",\"dose\":\"0.5정\",\"caution\":\"혈액희석제\"}");
        g.get("/api/medications")
                .andExpect(jsonPath("$[0].name").value("와파린정"))
                .andExpect(jsonPath("$[0].caution").value("혈액희석제"))
                .andExpect(jsonPath("$[1].name").value("타이레놀"));
    }

    @Test
    @DisplayName("1단계(긴급조회)에는 약 이름과 주의 약 종류가 함께 보인다")
    void emergencyShowsNameAndCaution() throws Exception {
        anonymous().post(emergency(judge()), "")
                .andExpect(jsonPath("$.medications[0].name").value("와파린정 2mg"))
                .andExpect(jsonPath("$.medications[0].caution").value("혈액희석제"));
    }

    @Test
    @DisplayName("보호자가 이름을 숨기면 1단계에는 주의 약 종류만 남고, 상세 정보에는 이름이 보인다")
    void hideName() throws Exception {
        TestUser g = judge();
        g.patch("/api/medications/" + medId(g, "와파린정 2mg"), "{\"nameHidden\":true}")
                .andExpect(jsonPath("$.nameHidden").value(true));
        anonymous().post(emergency(g), "")
                .andExpect(jsonPath("$.medications[0].name").doesNotExist())
                .andExpect(jsonPath("$.medications[0].caution").value("혈액희석제"))
                .andExpect(jsonPath("$.medications[1].name").value("도네페질정 5mg"));
        anonymous().get(detailUrl(g))
                .andExpect(jsonPath("$.medications[0].name").value("와파린정 2mg"))
                .andExpect(jsonPath("$.medications[0].caution").value("혈액희석제"));
    }

    @Test
    @DisplayName("긴급조회 노출을 끄면 1단계에서 빠진다")
    void hideFromEmergency() throws Exception {
        TestUser g = judge();
        g.patch("/api/medications/" + medId(g, "도네페질정 5mg"), "{\"emergencyVisible\":false}");
        anonymous().post(emergency(g), "").andExpect(jsonPath("$.medications.length()").value(1));
    }

    @Test
    @DisplayName("고치면 보낸 것만 바뀌고, 빈 문자열을 보내면 복용 시간·주의 약 종류가 지워진다")
    void patch() throws Exception {
        TestUser g = judge();
        String id = medId(g, "와파린정 2mg");
        g.patch("/api/medications/" + id, "{\"dose\":\"0.5알\"}")
                .andExpect(jsonPath("$.dose").value("0.5알"))
                .andExpect(jsonPath("$.timing").value("하루 1번 아침 식후"))
                .andExpect(jsonPath("$.caution").value("혈액희석제"));
        g.patch("/api/medications/" + id, "{\"timing\":\"\",\"caution\":\"\"}")
                .andExpect(jsonPath("$.timing").doesNotExist())
                .andExpect(jsonPath("$.caution").doesNotExist())
                .andExpect(jsonPath("$.dose").value("0.5알"));
    }

    @Test
    @DisplayName("빈 이름·빈 용량으로는 고칠 수 없다")
    void blankPatch() throws Exception {
        TestUser g = judge();
        String id = medId(g, "와파린정 2mg");
        g.patch("/api/medications/" + id, "{\"name\":\" \"}").andExpect(status().isBadRequest());
        g.patch("/api/medications/" + id, "{\"dose\":\"\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("지우면 목록과 1단계에서 사라지고, 다른 가구의 약은 고치거나 지울 수 없다")
    void deleteAndScope() throws Exception {
        TestUser g = judge();
        String id = medId(g, "도네페질정 5mg");
        TestUser stranger = judge();
        stranger.patch("/api/medications/" + id, "{\"dose\":\"10알\"}").andExpect(status().isNotFound());
        stranger.delete("/api/medications/" + id).andExpect(status().isNotFound());
        g.delete("/api/medications/" + id).andExpect(status().isNoContent());
        g.get("/api/medications").andExpect(jsonPath("$.length()").value(1));
        anonymous().post(emergency(g), "").andExpect(jsonPath("$.medications.length()").value(1));
        g.delete("/api/medications/" + id).andExpect(status().isNotFound());
    }
}
