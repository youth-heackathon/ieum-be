package com.ieum.access;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import com.ieum.ApiDocs;
import com.ieum.access.Access.Card;
import com.ieum.care.Membership;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static com.ieum.care.RecipientController.Item.DETAIL;
import static org.springframework.http.HttpStatus.*;

/** 보호자 쪽: QR·코드 발급, 열람 기록, 상세조회 동의, 알림. */
@Tag(name = ApiDocs.GUARDIAN_ACCESS)
@RestController
@RequestMapping("/api")
public class GuardianAccessController {

    static final int VIEW_MINUTES = 60; // [가정] 상세조회 동의 뒤 열람 기간 (FR-9.5 미정)

    record LogEntry(@Schema(allowableValues = {"emergency", "detail_request", "detail_view"}) String kind,
                    OffsetDateTime createdAt, String userAgent) {}

    record PendingRequest(UUID id, OffsetDateTime createdAt, OffsetDateTime deadlineAt, long remainingSeconds,
                          String userAgent, String requesterPhone) {}

    record Decision(UUID id, @Schema(allowableValues = {"approved", "rejected"}) String status, OffsetDateTime viewExpiresAt) {}

    record Notification(UUID id, @Schema(allowableValues = {"emergency_view", "detail_request"}) String kind, UUID refId,
                        OffsetDateTime createdAt, OffsetDateTime readAt) {}

    /** 마감 전이고 아직 아무도 처리하지 않은 내 가구의 요청만. 조건부 UPDATE 한 번이라 동시에 눌러도 한 명만 처리된다. */
    private static final String DECIDABLE = "where id = ? and recipient_id = ? and status = 'pending' and deadline_at > now()";

    private final JdbcClient db;
    private final Access access;

    public GuardianAccessController(JdbcClient db, Access access) {
        this.db = db;
        this.access = access;
    }

    /** 발급·재발급. 연결된 보호자 누구나. */
    @Operation(summary = "QR·8자리 코드 발급/재발급", description = "다시 발급하면 이전 QR·코드는 바로 무효. QR 이미지는 프론트가 /a/{qrToken} 주소로 만든다.")
    @PostMapping("/access-codes")
    @ResponseStatus(CREATED)
    public Card issue(@SessionAttribute(GUARDIAN) UUID me) {
        return access.issue(Membership.of(db, me).recipientId());
    }

    @Operation(summary = "지금 쓰는 QR·코드")
    @GetMapping("/access-codes")
    public Card current(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("select qr_token, code8, issued_at from access_codes where recipient_id = ? and revoked_at is null")
                .param(Membership.of(db, me).recipientId()).query(Card.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "아직 QR을 만들지 않았어요"));
    }

    @Operation(summary = "열람 기록", description = "누가 언제 조회했는지(기기정보), 최신순 100건.")
    @GetMapping("/access-logs")
    public List<LogEntry> logs(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("""
                        select l.kind, l.created_at, l.user_agent
                        from access_logs l join access_codes a on a.id = l.access_code_id
                        where a.recipient_id = ? order by l.created_at desc limit 100""")
                .param(Membership.of(db, me).recipientId()).query(LogEntry.class).list();
    }

    @Operation(summary = "대기 중인 상세조회 요청", description = "마감 전이고 아직 처리 안 된 것만. remainingSeconds로 남은 시간을 보여 준다.")
    @GetMapping("/detail-requests")
    public List<PendingRequest> pending(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("""
                        select id, created_at, deadline_at,
                               ceil(extract(epoch from deadline_at - now()))::bigint as remaining_seconds,
                               user_agent, requester_phone
                        from detail_requests
                        where recipient_id = ? and status = 'pending' and deadline_at > now() order by created_at""")
                .param(Membership.of(db, me).recipientId()).query(PendingRequest.class).list();
    }

    @Operation(summary = "상세조회 동의", description = "10분 안에만. 동의하면 요청자가 60분 동안 상세 정보를 본다. 상세조회 제공 동의가 없거나, 마감이 지났거나, 이미 처리했거나, 다른 가구의 요청이면 409.")
    @PostMapping("/detail-requests/{id}/approve")
    public Decision approve(@PathVariable UUID id, @SessionAttribute(GUARDIAN) UUID me) {
        UUID recipientId = Membership.of(db, me).recipientId();
        if (!access.allows(recipientId, DETAIL))
            throw new ResponseStatusException(CONFLICT, "상세조회 제공에 동의해야 보여 줄 수 있어요. 동의 화면에서 먼저 동의해 주세요");
        return db.sql("""
                        update detail_requests set status = 'approved', decided_by = ?, decided_at = now(),
                               view_token = ?, view_expires_at = now() + make_interval(mins => ?)
                        """ + DECIDABLE + " returning id, status, view_expires_at")
                .params(me, Access.randomToken(), VIEW_MINUTES, id, recipientId)
                .query(Decision.class).optional().orElseThrow(GuardianAccessController::notDecidable);
    }

    @Operation(summary = "상세조회 거절")
    @PostMapping("/detail-requests/{id}/reject")
    public Decision reject(@PathVariable UUID id, @SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("update detail_requests set status = 'rejected', decided_by = ?, decided_at = now() "
                        + DECIDABLE + " returning id, status, view_expires_at")
                .params(me, id, Membership.of(db, me).recipientId())
                .query(Decision.class).optional().orElseThrow(GuardianAccessController::notDecidable);
    }

    @Operation(summary = "알림 (폴링)", description = "최신순 50건. emergency_view의 refId는 열람 기록, detail_request의 refId는 상세조회 요청 id.")
    @GetMapping("/notifications")
    public List<Notification> notifications(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("select id, kind, ref_id, created_at, read_at from notifications where guardian_id = ? order by created_at desc limit 50")
                .param(me).query(Notification.class).list();
    }

    @Operation(summary = "알림 모두 읽음")
    @PostMapping("/notifications/read")
    @ResponseStatus(NO_CONTENT)
    public void readAll(@SessionAttribute(GUARDIAN) UUID me) {
        db.sql("update notifications set read_at = now() where guardian_id = ? and read_at is null").param(me).update();
    }

    private static ResponseStatusException notDecidable() {
        return new ResponseStatusException(CONFLICT, "이미 처리됐거나 10분이 지난 요청이에요");
    }
}
