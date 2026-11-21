package com.ieum.care;

import com.ieum.ApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static com.ieum.demo.DemoController.DEMO;

/** 보호자 홈. 체험 노트북은 이것 하나만 2초마다 폴링한다 (단계 + 열람·요청·알림). */
@Tag(name = ApiDocs.HOME)
@RestController
public class HomeController {

    record RecipientSummary(UUID id, String name) {}

    record DemoState(UUID sessionId, int step, OffsetDateTime expiresAt) {}

    record PendingRequest(UUID id, OffsetDateTime deadlineAt, long remainingSeconds, String userAgent) {}

    record StaleRecord(UUID id, String category, String summary, OffsetDateTime lastCheckedAt) {}

    record RecentAccess(@Schema(allowableValues = {"emergency", "detail_request", "detail_view"}) String kind,
                        OffsetDateTime createdAt, String userAgent) {}

    /** recipient가 null이면 아직 보호대상자를 등록·연결하지 않은 것. 되묻기(꼭 남길 정보)는 AI 단계에서 붙는다. */
    record Home(RecipientSummary recipient,
                @Schema(allowableValues = {"primary", "member"}) String role,
                @Schema(description = "체험 세션일 때만. 실제 보호자는 null") DemoState demo,
                @Schema(description = "QR·8자리 코드를 발급했는지") boolean hasAccessCode,
                long unreadNotifications,
                List<PendingRequest> pendingDetailRequests,
                @Schema(description = "마지막 확인이 30일 지난 기록") List<StaleRecord> staleRecords,
                @Schema(description = "최근 열람 5건") List<RecentAccess> recentAccess) {}

    private final JdbcClient db;

    public HomeController(JdbcClient db) {
        this.db = db;
    }

    @Operation(summary = "홈 (폴링)", description = "보호대상자, 대기 중인 상세조회 요청, 안 읽은 알림 수, 30일 지난 기록, 최근 열람, 체험 단계를 한 번에 준다.")
    @GetMapping("/api/home")
    public Home home(@SessionAttribute(GUARDIAN) UUID me, @SessionAttribute(name = DEMO, required = false) UUID demoId) {
        DemoState demo = demoId == null ? null
                : db.sql("select id as session_id, step, expires_at from demo_sessions where id = ?")
                        .param(demoId).query(DemoState.class).optional().orElse(null);
        long unread = db.sql("select count(*) from notifications where guardian_id = ? and read_at is null")
                .param(me).query(Long.class).single();
        var membership = Membership.find(db, me);
        if (membership.isEmpty()) return new Home(null, null, demo, false, unread, List.of(), List.of(), List.of());

        UUID r = membership.get().recipientId();
        return new Home(
                db.sql("select id, name from care_recipients where id = ?").param(r).query(RecipientSummary.class).single(),
                membership.get().role(),
                demo,
                db.sql("select exists (select 1 from access_codes where recipient_id = ? and revoked_at is null)")
                        .param(r).query(Boolean.class).single(),
                unread,
                db.sql("select id, deadline_at, ceil(extract(epoch from deadline_at - now()))::bigint as remaining_seconds, user_agent "
                                + "from detail_requests where recipient_id = ? and status = 'pending' and deadline_at > now() order by created_at")
                        .param(r).query(PendingRequest.class).list(),
                db.sql("select id, category, summary, coalesce(last_checked_at, confirmed_at) as last_checked_at from care_records "
                                + "where recipient_id = ? and confirmed_at is not null "
                                + "and coalesce(last_checked_at, confirmed_at) < now() - interval '30 days' order by 4")
                        .param(r).query(StaleRecord.class).list(),
                db.sql("select l.kind, l.created_at, l.user_agent from access_logs l join access_codes a on a.id = l.access_code_id "
                                + "where a.recipient_id = ? order by l.created_at desc limit 5")
                        .param(r).query(RecentAccess.class).list());
    }
}
