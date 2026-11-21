package com.ieum.access;

import com.ieum.access.Access.Target;
import com.ieum.safety.IpHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.ieum.auth.AuthController.PHONE;
import static com.ieum.care.RecipientController.Item.DETAIL;
import static com.ieum.care.RecipientController.Item.EMERGENCY;
import static org.springframework.http.HttpStatus.*;

/** 임시보호자·의료진 쪽. 로그인 없이 QR 토큰(또는 8자리 코드)으로 들어온다. */
@RestController
@RequestMapping("/api/public")
public class PublicAccessController {

    static final int MAX_CODE_FAILS_PER_HOUR = 10;
    static final int MAX_DETAIL_REQUESTS_PER_HOUR = 10;
    static final String LOGGED_NOTICE = "조회하면 기기 정보가 기록되고 보호자에게 알림이 가요";
    static final String DETAIL_NOTICE = "보호자가 적은 돌봄 방법이며 의료 지시가 아닙니다.";

    record ResolveReq(String token, String code) {}

    record Resolved(String token, boolean demo) {}

    record EmergencyItem(String category, String summary) {}

    record EmergencyMed(String name, boolean highRisk) {}

    /** allowed=false면 보호자가 공개에 동의하지 않은 것. 기록·알림은 그래도 남는다. */
    record Emergency(boolean allowed, List<EmergencyItem> items, List<EmergencyMed> medications, boolean logged, String notice) {}

    record DetailReq(@Pattern(regexp = PHONE, message = "휴대폰 번호 형식이 아니에요") String requesterPhone) {}

    /** viewToken은 보호자가 동의했고 열람 시간이 남아 있을 때만 온다. */
    record DetailStatus(UUID id, String status, OffsetDateTime deadlineAt, String viewToken, OffsetDateTime viewExpiresAt) {}

    record DetailRecord(String category, String summary, String quote, OffsetDateTime lastCheckedAt) {}

    record DetailMed(String name, String dose, boolean highRisk, String pharmacyPhone, String quote) {}

    record Detail(String notice, OffsetDateTime lastCheckedAt, List<DetailRecord> records, List<DetailMed> medications,
                  OffsetDateTime viewExpiresAt) {}

    record DemoStep(UUID sessionId, int step) {}

    private record Grant(UUID recipientId, UUID accessCodeId, OffsetDateTime viewExpiresAt) {}

    /** 10분 마감은 서버 시각으로 판정한다. */
    private static final String STATUS_SQL = """
            select id,
                   case when status = 'pending' and deadline_at <= now() then 'expired' else status end as status,
                   deadline_at,
                   case when status = 'approved' and view_expires_at > now() then view_token end as view_token,
                   view_expires_at
            from detail_requests where id = ?""";

    private final JdbcClient db;
    private final Access access;
    private final IpHasher ips;

    public PublicAccessController(JdbcClient db, Access access, IpHasher ips) {
        this.db = db;
        this.access = access;
        this.ips = ips;
    }

    /**
     * QR 토큰이나 8자리 코드를 확인한다. 8자리는 무작위 대입을 막으려고 IP당 1시간에 10번 틀리면 막는다.
     * 일부러 @Transactional이 아니다: 틀린 시도 기록이 404와 함께 롤백되면 안 된다.
     */
    @PostMapping("/access/resolve")
    public Resolved resolve(@RequestBody ResolveReq req, HttpServletRequest http) {
        if (req.token() != null) return find("a.qr_token = ?", req.token()).orElseThrow(() ->
                new ResponseStatusException(NOT_FOUND, "사용할 수 없는 QR이에요. 보호자에게 새 카드를 받아 주세요"));
        String code = req.code() == null ? "" : req.code().replaceAll("\\D", "");
        if (code.length() != 8) throw new ResponseStatusException(BAD_REQUEST, "카드에 적힌 8자리 숫자를 입력해 주세요");

        String ipHash = ips.hash(http);
        long fails = db.sql("select count(*) from code_attempts where ip_hash = ? and created_at > now() - interval '1 hour'")
                .param(ipHash).query(Long.class).single();
        if (fails >= MAX_CODE_FAILS_PER_HOUR)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "여러 번 틀렸어요. 1시간 뒤에 다시 시도하거나 QR을 찍어 주세요");
        return find("a.code8 = ?", code).orElseGet(() -> {
            db.sql("insert into code_attempts (ip_hash) values (?)").param(ipHash).update();
            throw new ResponseStatusException(NOT_FOUND, "코드를 다시 확인해 주세요");
        });
    }

    /** 의료진: 인증·대기 없이 보호자가 고른 필수정보만. 조회할 때마다 기록하고 보호자에게 알린다. */
    @PostMapping("/access/{token}/emergency")
    @Transactional
    public Emergency emergency(@PathVariable String token, HttpServletRequest http) {
        Target t = access.byToken(token);
        UUID logId = access.log(t.codeId(), "emergency", http);
        access.notifyGuardians(t.recipientId(), "emergency_view", logId);
        if (!access.allows(t.recipientId(), EMERGENCY)) return new Emergency(false, List.of(), List.of(), true, LOGGED_NOTICE);

        var items = db.sql("""
                        select category, summary from care_records
                        where recipient_id = ? and confirmed_at is not null and emergency_visible order by created_at""")
                .param(t.recipientId()).query(EmergencyItem.class).list();
        var meds = db.sql("""
                        select name, high_risk from medications
                        where recipient_id = ? and confirmed_at is not null and emergency_visible order by high_risk desc, name""")
                .param(t.recipientId()).query(EmergencyMed.class).list();
        return new Emergency(true, items, meds, true, LOGGED_NOTICE);
    }

    /** 간병인: 상세조회 요청. 10분 안에 보호자가 동의해야 열린다. 요청자 번호는 선택, 30일 뒤 파기. */
    @PostMapping("/access/{token}/detail-requests")
    @ResponseStatus(CREATED)
    @Transactional
    public DetailStatus requestDetail(@PathVariable String token, @Valid @RequestBody(required = false) DetailReq body,
                                      HttpServletRequest http) {
        Target t = access.byToken(token);
        String ipHash = ips.hash(http);
        long recent = db.sql("""
                        select count(*) from access_logs
                        where ip_hash = ? and kind = 'detail_request' and created_at > now() - interval '1 hour'""")
                .param(ipHash).query(Long.class).single();
        if (recent >= MAX_DETAIL_REQUESTS_PER_HOUR)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 뒤 다시 해 주세요");

        String phone = body == null || body.requesterPhone() == null ? null : body.requesterPhone().replaceAll("\\D", "");
        UUID id = db.sql("""
                        insert into detail_requests (recipient_id, access_code_id, requester_phone, phone_purge_at,
                                                     user_agent, ip_hash, deadline_at)
                        values (?, ?, ?, now() + interval '30 days', ?, ?, now() + interval '10 minutes') returning id""")
                .params(t.recipientId(), t.codeId(), phone, http.getHeader("User-Agent"), ipHash)
                .query(UUID.class).single();
        access.log(t.codeId(), "detail_request", http);
        access.notifyGuardians(t.recipientId(), "detail_request", id);
        return status(id);
    }

    /** 요청자 화면이 2초마다 부른다. 요청 id(UUID)를 아는 요청자만 볼 수 있다. */
    @GetMapping("/detail-requests/{id}")
    public DetailStatus status(@PathVariable UUID id) {
        return db.sql(STATUS_SQL).param(id).query(DetailStatus.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "요청을 찾을 수 없어요"));
    }

    /** 상세 정보. 볼 때마다 동의를 다시 확인한다 → 승인 뒤 동의를 거두면 바로 막힌다. */
    @GetMapping("/detail/{viewToken}")
    @Transactional
    public Detail detail(@PathVariable String viewToken, HttpServletRequest http) {
        Grant g = db.sql("""
                        select recipient_id, access_code_id, view_expires_at from detail_requests
                        where view_token = ? and status = 'approved' and view_expires_at > now()""")
                .param(viewToken).query(Grant.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "열람 시간이 끝났거나 없는 주소예요. 다시 요청해 주세요"));
        if (!access.allows(g.recipientId(), DETAIL))
            throw new ResponseStatusException(FORBIDDEN, "보호자가 상세 정보 제공에 동의하지 않았어요");
        access.log(g.accessCodeId(), "detail_view", http);

        var records = db.sql("""
                        select category, summary, quote_text as quote, last_checked_at from care_records
                        where recipient_id = ? and confirmed_at is not null order by created_at""")
                .param(g.recipientId()).query(DetailRecord.class).list();
        var meds = db.sql("""
                        select name, dose, high_risk, pharmacy_phone, quote_text as quote from medications
                        where recipient_id = ? and confirmed_at is not null order by high_risk desc, name""")
                .param(g.recipientId()).query(DetailMed.class).list();
        OffsetDateTime lastChecked = records.stream().map(DetailRecord::lastCheckedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new Detail(DETAIL_NOTICE, lastChecked, records, meds, g.viewExpiresAt());
    }

    /** 체험: 화면 속 QR을 폰으로 찍으면 폰이 이걸로 같은 체험 단계를 따라간다 (2초 폴링). */
    @GetMapping("/access/{token}/demo")
    public DemoStep demoStep(@PathVariable String token) {
        return db.sql("""
                        select s.id as session_id, s.step from access_codes a
                        join care_recipients r on r.id = a.recipient_id
                        join demo_sessions s on s.id = r.demo_session_id
                        where a.qr_token = ? and a.revoked_at is null""")
                .param(token).query(DemoStep.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "체험용 QR이 아니에요"));
    }

    private java.util.Optional<Resolved> find(String condition, String value) {
        return db.sql("""
                        select a.qr_token as token, r.demo_session_id is not null as demo
                        from access_codes a join care_recipients r on r.id = a.recipient_id
                        where a.revoked_at is null and\s""" + condition)
                .param(value).query(Resolved.class).optional();
    }
}
