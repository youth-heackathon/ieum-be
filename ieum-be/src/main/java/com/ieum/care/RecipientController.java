package com.ieum.care;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.ieum.ApiDocs;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;

/** 보호대상자 등록과 항목별 동의. */
@Tag(name = ApiDocs.RECIPIENT)
@RestController
@RequestMapping("/api")
public class RecipientController {

    public enum Item { PERSONAL, HEALTH, EMERGENCY, DETAIL, OVERSEAS_AI }

    record RecipientReq(@NotBlank @Size(max = 50) String name, @Past LocalDate birthDate) {}

    record Recipient(UUID id, String name, LocalDate birthDate) {}

    /** selfConsent: 보호대상자 본인 동의, guardianOfRecord: 후견인 있음. 모두 바꿀 것만 보내면 된다 (빠진 값은 유지). */
    record ConsentReq(Boolean selfConsent, Boolean guardianOfRecord, @NotNull Map<Item, @NotNull Boolean> items) {}

    record ConsentItem(String item, boolean agreed, OffsetDateTime agreedAt, UUID agreedBy) {}

    record Consents(boolean selfConsent, boolean guardianOfRecord, List<ConsentItem> items) {}

    private record Flags(boolean selfConsent, boolean guardianOfRecord) {}

    private final JdbcClient db;

    public RecipientController(JdbcClient db) {
        this.db = db;
    }

    @Operation(summary = "보호대상자 등록", description = "등록한 보호자가 핵심 보호자(primary)가 된다. 보호자 1명당 1명 [가정].")
    @PostMapping("/recipients")
    @ResponseStatus(CREATED)
    @Transactional
    public Recipient create(@Valid @RequestBody RecipientReq req, @SessionAttribute(GUARDIAN) UUID me) {
        if (Membership.find(db, me).isPresent())
            throw new ResponseStatusException(CONFLICT, "이미 연결된 보호대상자가 있어요");
        Recipient r = db.sql("insert into care_recipients (name, birth_date) values (?, ?) returning id, name, birth_date")
                .params(req.name(), req.birthDate()).query(Recipient.class).single();
        db.sql("insert into recipient_guardians (recipient_id, guardian_id, role, consent_at) values (?, ?, 'primary', now())")
                .params(r.id(), me).update();
        return r;
    }

    @Operation(summary = "항목별 동의 저장", description = "보낸 것만 바뀐다. 긴급조회·상세조회 공개는 그 항목 동의와 함께 본인 동의(selfConsent)나 후견인(guardianOfRecord)이 있어야 서버가 허용한다.")
    @PostMapping("/consents")
    @Transactional
    public Consents saveConsents(@Valid @RequestBody ConsentReq req, @SessionAttribute(GUARDIAN) UUID me) {
        UUID recipientId = Membership.of(db, me).recipientId();
        db.sql("""
                        update care_recipients set self_consent = coalesce(?::boolean, self_consent),
                        guardian_of_record = coalesce(?::boolean, guardian_of_record) where id = ?""")
                .params(req.selfConsent(), req.guardianOfRecord(), recipientId).update();
        req.items().forEach((item, agreed) -> db.sql("""
                        insert into consents (recipient_id, item, agreed, agreed_by) values (?, ?, ?, ?)
                        on conflict (recipient_id, item)
                        do update set agreed = excluded.agreed, agreed_at = now(), agreed_by = excluded.agreed_by""")
                .params(recipientId, item.name(), agreed, me).update());
        return consents(me);
    }

    @Operation(summary = "동의 현황", description = "항목마다 동의 여부·시각·동의한 보호자.")
    @GetMapping("/consents")
    public Consents consents(@SessionAttribute(GUARDIAN) UUID me) {
        UUID recipientId = Membership.of(db, me).recipientId();
        Flags f = db.sql("select self_consent, guardian_of_record from care_recipients where id = ?")
                .param(recipientId).query(Flags.class).single();
        List<ConsentItem> items = db.sql("select item, agreed, agreed_at, agreed_by from consents where recipient_id = ? order by item")
                .param(recipientId).query(ConsentItem.class).list();
        return new Consents(f.selfConsent(), f.guardianOfRecord(), items);
    }
}
