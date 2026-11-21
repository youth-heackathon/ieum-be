package com.ieum.care;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static org.springframework.http.HttpStatus.*;

/** 보호자 연결: 핵심 보호자가 초대코드 발급 → 다른 보호자가 입력·동의 → 연결. */
@RestController
@RequestMapping("/api/guardians")
public class GuardianController {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 헷갈리는 0·O·1·I 제외
    private static final SecureRandom RANDOM = new SecureRandom();

    record Invite(String code, OffsetDateTime expiresAt) {}

    record RedeemReq(@NotBlank String code, @AssertTrue(message = "연결에 동의해야 해요") boolean agreed) {}

    record Linked(UUID id, String name, String role, OffsetDateTime joinedAt) {}

    private final JdbcClient db;

    public GuardianController(JdbcClient db) {
        this.db = db;
    }

    /** 8자리, 24시간, 1회용. */
    @PostMapping("/invite-codes")
    @ResponseStatus(CREATED)
    public Invite issue(@SessionAttribute(GUARDIAN) UUID me) {
        Membership m = Membership.of(db, me);
        if (!m.isPrimary()) throw new ResponseStatusException(FORBIDDEN, "핵심 보호자만 초대코드를 만들 수 있어요");
        return db.sql("""
                        insert into guardian_invites (recipient_id, code, issued_by, expires_at)
                        values (?, ?, ?, now() + interval '24 hours') returning code, expires_at""")
                .params(m.recipientId(), randomCode(8), me).query(Invite.class).single();
    }

    @PostMapping("/invite-codes/redeem")
    @Transactional
    public List<Linked> redeem(@Valid @RequestBody RedeemReq req, @SessionAttribute(GUARDIAN) UUID me) {
        if (Membership.find(db, me).isPresent())
            throw new ResponseStatusException(CONFLICT, "이미 연결된 보호대상자가 있어요");
        // 사용 처리를 조건부 UPDATE 한 번으로 해서 같은 코드를 두 명이 동시에 써도 한 명만 성공한다
        UUID recipientId = db.sql("""
                        update guardian_invites set used_at = now(), used_by = :me
                        where code = :code and used_at is null and expires_at > now()
                        returning recipient_id""")
                .param("me", me).param("code", req.code().trim().toUpperCase())
                .query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "초대코드가 없거나 이미 쓰였거나 만료됐어요"));
        db.sql("insert into recipient_guardians (recipient_id, guardian_id, role, consent_at) values (?, ?, 'member', now())")
                .params(recipientId, me).update();
        return list(me);
    }

    @GetMapping
    public List<Linked> list(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("""
                        select g.id, g.name, rg.role, rg.joined_at
                        from recipient_guardians rg join guardians g on g.id = rg.guardian_id
                        where rg.recipient_id = ? order by rg.joined_at""")
                .param(Membership.of(db, me).recipientId()).query(Linked.class).list();
    }

    static String randomCode(int length) {
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
