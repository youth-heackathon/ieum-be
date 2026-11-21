package com.ieum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("보호자 알림 (앱 안 알림, 폴링)")
class NotificationTest extends ApiTest {

    @Test
    @DisplayName("알림은 최신순이고, 모두 읽음 처리하면 읽은 시각이 채워진다")
    void newestFirstAndRead() throws Exception {
        TestUser g = judge();
        String token = qrToken(g);
        anonymous().post("/api/public/access/" + token + "/emergency", "");
        anonymous().post("/api/public/access/" + token + "/detail-requests", "");
        g.get("/api/notifications")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("detail_request"))
                .andExpect(jsonPath("$[1].kind").value("emergency_view"))
                .andExpect(jsonPath("$[0].readAt").doesNotExist());
        g.post("/api/notifications/read", "").andExpect(status().isNoContent());
        g.get("/api/notifications").andExpect(jsonPath("$[0].readAt").isNotEmpty());
    }

    @Test
    @DisplayName("다른 가구 보호자에게는 알림이 가지 않는다")
    void scopedToHousehold() throws Exception {
        TestUser mine = judge(), other = judge();
        anonymous().post("/api/public/access/" + qrToken(mine) + "/emergency", "");
        other.get("/api/notifications").andExpect(jsonPath("$.length()").value(0));
    }
}
