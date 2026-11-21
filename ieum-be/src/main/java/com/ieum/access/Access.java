package com.ieum.access;

import com.ieum.care.RecipientController.Item;
import com.ieum.safety.IpHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** QR·연결코드 발급, 조회 기록·알림, 공개 범위 판정. 보호자 쪽과 조회하는 쪽이 같이 쓴다. */
@Component
public class Access {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 지갑 카드에 들어가는 것. 건강정보·이름·번호는 넣지 않는다 (FR-7.2). */
    public record Card(String qrToken, String code8, OffsetDateTime issuedAt) {}

    /** 유효한 QR이 가리키는 대상. */
    public record Target(UUID codeId, UUID recipientId) {}

    private final JdbcClient db;
    private final IpHasher ips;

    public Access(JdbcClient db, IpHasher ips) {
        this.db = db;
        this.ips = ips;
    }

    /** 다시 발급하면 이전 QR·코드는 무효가 된다 (FR-7.4). */
    // ponytail: 8자리가 다른 활성 코드와 겹치면(활성 코드 수/1억 확률) 409. 다시 누르면 된다
    @Transactional
    public Card issue(UUID recipientId) {
        db.sql("update access_codes set revoked_at = now() where recipient_id = ? and revoked_at is null")
                .param(recipientId).update();
        return db.sql("insert into access_codes (recipient_id, qr_token, code8) values (?, ?, ?) returning qr_token, code8, issued_at")
                .params(recipientId, randomToken(), "%08d".formatted(RANDOM.nextInt(100_000_000)))
                .query(Card.class).single();
    }

    public Target byToken(String qrToken) {
        return db.sql("select id as code_id, recipient_id from access_codes where qr_token = ? and revoked_at is null")
                .param(qrToken).query(Target.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "사용할 수 없는 QR이에요. 보호자에게 새 카드를 받아 주세요"));
    }

    /** 기기정보(User-Agent)와 IP 해시만 남긴다. 위치는 받지 않는다 (FR-8.3·8.5). */
    public UUID log(UUID codeId, String kind, HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        if (ua != null && ua.length() > 500) ua = ua.substring(0, 500);
        return db.sql("insert into access_logs (access_code_id, kind, user_agent, ip_hash) values (?, ?, ?, ?) returning id")
                .params(codeId, kind, ua, ips.hash(req)).query(UUID.class).single();
    }

    /** 연결된 보호자 전원에게 앱 안 알림 (실제 문자 없음). */
    public void notifyGuardians(UUID recipientId, String kind, UUID refId) {
        db.sql("insert into notifications (guardian_id, kind, ref_id) select guardian_id, ?, ? from recipient_guardians where recipient_id = ?")
                .params(kind, refId, recipientId).update();
    }

    /** 그 항목에 동의했고, 본인 동의나 후견인이 있어야 공개한다 (FR-4.3·4.4). */
    public boolean allows(UUID recipientId, Item item) {
        return db.sql("""
                        select exists (select 1 from consents c join care_recipients r on r.id = c.recipient_id
                                       where c.recipient_id = ? and c.item = ? and c.agreed
                                         and (r.self_consent or r.guardian_of_record))""")
                .params(recipientId, item.name()).query(Boolean.class).single();
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
