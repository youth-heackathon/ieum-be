package com.ieum.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.ieum.ApiDocs;
import com.ieum.access.Access;
import com.ieum.safety.IpHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static com.ieum.auth.AuthController.freshSession;
import static org.springframework.http.HttpStatus.*;

/**
 * 심사위원 체험 모드. 세션을 만들면 가상 가구(보호자 박지은 + 어머니)를 복제하고 그 보호자로 로그인시킨다.
 * → 보호자 API가 체험에서도 그대로 동작한다. 24시간 뒤 ieum_cleanup()이 가구째 지운다.
 */
@Tag(name = ApiDocs.DEMO)
@RestController
@RequestMapping("/api/demo/sessions")
public class DemoController {

    public static final String DEMO = "demoSessionId";
    static final int MAX_SESSIONS_PER_IP_DAY = 100; // 공개 URL 남용 방지. 행사장 공용 와이파이를 감안해 넉넉히

    record DemoSession(UUID id, int step, OffsetDateTime expiresAt) {}

    record StepReq(@Min(1) @Max(5) int step) {} // 4단계 + 끝 화면

    private record Created(UUID sessionId, UUID guardianId, UUID recipientId) {}

    /** 한 문장(CTE)이라 중간에 실패해도 반쪽 가구가 남지 않는다. */
    private static final String HOUSEHOLD = """
            with s as (
                insert into demo_sessions (ip_hash) values (:ip) returning id
            ), g as (
                insert into guardians (name, birth_date, demo_session_id)
                select '박지은', date '1974-04-12', id from s returning id
            ), r as (
                insert into care_recipients (name, birth_date, self_consent, demo_session_id)
                select '김순자', date '1946-09-03', true, id from s returning id
            ), link as (
                insert into recipient_guardians (recipient_id, guardian_id, role, consent_at)
                select r.id, g.id, 'primary', now() from r, g
            ), agree as (
                insert into consents (recipient_id, item, agreed, agreed_by)
                select r.id, item, true, g.id
                from r, g, unnest(array['PERSONAL', 'HEALTH', 'EMERGENCY', 'DETAIL', 'OVERSEAS_AI']) item
            ), records as (
                insert into care_records (recipient_id, category, summary, quote_text, verify_status,
                                          emergency_visible, confirmed_at, last_checked_at)
                select r.id, v.category, v.summary, v.quote, 'pass', v.emergency, now(), now()
                from r, (values
                    ('알러지', '페니실린 알레르기가 있어요',
                     '페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요', true),
                    ('진정법', '불안해하시면 손을 잡고 고향의 봄을 불러 드리면 가라앉아요',
                     '불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요', false),
                    ('특이사항', '저녁 5시쯤 되면 집에 가야 한다며 나가려고 하세요',
                     '저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요', false)
                ) v (category, summary, quote, emergency)
            ), meds as (
                insert into medications (recipient_id, name, dose, quote_text, verify_status, ai_source,
                                         emergency_visible, high_risk, confirmed_at)
                select r.id, v.name, v.dose, v.quote, 'pass', 'demo', true, v.high_risk, now()
                from r, (values
                    ('와파린정 2mg', '하루 1번 아침 식후 1알', '와파린정2mg 1일 1회 아침 식후 1정', true),
                    ('도네페질정 5mg', '하루 1번 자기 전 1알', '도네페질정5mg 1일 1회 취침 전 1정', false)
                ) v (name, dose, quote, high_risk)
            )
            select s.id as session_id, g.id as guardian_id, r.id as recipient_id from s, g, r""";

    private final JdbcClient db;
    private final IpHasher ips;
    private final Access access;

    public DemoController(JdbcClient db, IpHasher ips, Access access) {
        this.db = db;
        this.ips = ips;
        this.access = access;
    }

    @Operation(summary = "체험 시작 / 처음부터", description = "가상 가구(보호자 박지은과 어머니)를 새로 만들고 그 보호자로 로그인한다. QR도 발급돼 있다. 24시간 뒤 삭제, 같은 IP에서 하루 100번까지.")
    @PostMapping
    @ResponseStatus(CREATED)
    public DemoSession start(HttpServletRequest http) {
        String ipHash = ips.hash(http);
        long recent = db.sql("select count(*) from demo_sessions where ip_hash = ? and created_at > now() - interval '1 day'")
                .param(ipHash).query(Long.class).single();
        if (recent >= MAX_SESSIONS_PER_IP_DAY)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "오늘은 체험을 너무 많이 시작했어요. 내일 다시 해 주세요");

        Created c = db.sql(HOUSEHOLD).param("ip", ipHash).query(Created.class).single();
        access.issue(c.recipientId()); // 체험 화면에 바로 QR을 띄울 수 있게 (GET /api/access-codes)
        HttpSession session = freshSession(http);
        session.setAttribute(GUARDIAN, c.guardianId());
        session.setAttribute(DEMO, c.sessionId());
        return current(c.sessionId());
    }

    @Operation(summary = "체험 단계 조회 (노트북 폴링)")
    @GetMapping("/me")
    public DemoSession current(@SessionAttribute(name = DEMO, required = false) UUID id) {
        return db.sql("select id, step, expires_at from demo_sessions where id = ?")
                .param(id).query(DemoSession.class).optional().orElseThrow(DemoController::notFound);
    }

    @Operation(summary = "체험 단계 바꾸기", description = "1~4단계와 5(끝 화면).")
    @PatchMapping("/me")
    public DemoSession moveTo(@Valid @RequestBody StepReq req, @SessionAttribute(name = DEMO, required = false) UUID id) {
        return db.sql("update demo_sessions set step = ? where id = ? returning id, step, expires_at")
                .params(req.step(), id).query(DemoSession.class).optional().orElseThrow(DemoController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(NOT_FOUND, "체험 세션이 없거나 끝났어요. 처음부터 다시 시작해 주세요");
    }
}
