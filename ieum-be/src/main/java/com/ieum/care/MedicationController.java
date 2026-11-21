package com.ieum.care;

import com.ieum.ApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static org.springframework.http.HttpStatus.*;

/**
 * 약 관리. 약은 이름·용량·복용 시간으로 묶는다 (docs/이음수첩_AI_개인정보_약등록.md 3.2).
 * 사진 → OCR → 가림 → AI 정리 → 식약처 대조 경로는 AI 단계에서 붙고, 여기서는 보호자가 직접 넣고 고친다.
 */
@Tag(name = ApiDocs.CARE)
@RestController
@RequestMapping("/api/medications")
public class MedicationController {

    record Medication(UUID id, String name, String dose, String timing,
                      @Schema(description = "주의 약 종류 (예: 혈액희석제). null이면 일반 약") String caution,
                      @Schema(description = "1단계(긴급조회)에서 약 이름 숨김. 주의 약 종류는 보인다") boolean nameHidden,
                      boolean emergencyVisible, OffsetDateTime confirmedAt) {}

    record NewMedication(@NotBlank @Size(max = 100) String name,
                         @NotBlank(message = "용량은 꼭 확인해서 적어 주세요") @Size(max = 100) String dose,
                         @Size(max = 100) String timing,
                         @Schema(description = "식약처 대조가 붙기 전까지는 직접 적는다") @Size(max = 50) String caution,
                         @Schema(defaultValue = "false") Boolean nameHidden,
                         @Schema(defaultValue = "true") Boolean emergencyVisible) {}

    /** 보낸 것만 바뀐다. timing·caution은 빈 문자열("")을 보내면 지운다. */
    record MedicationPatch(@Size(max = 100) String name, @Size(max = 100) String dose, @Size(max = 100) String timing,
                           @Size(max = 50) String caution, Boolean nameHidden, Boolean emergencyVisible) {}

    private static final String COLUMNS = "id, name, dose, timing, caution, name_hidden, emergency_visible, confirmed_at";

    private final JdbcClient db;

    public MedicationController(JdbcClient db) {
        this.db = db;
    }

    @Operation(summary = "약 목록", description = "주의 약이 먼저 온다.")
    @GetMapping
    public List<Medication> list(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("select " + COLUMNS + " from medications where recipient_id = ? order by caution is null, created_at")
                .param(Membership.of(db, me).recipientId()).query(Medication.class).list();
    }

    @Operation(summary = "약 직접 등록", description = "보호자가 직접 적은 약은 바로 확정된다(맞아요, 저장). 긴급조회 노출은 기본으로 켜진다.")
    @PostMapping
    @ResponseStatus(CREATED)
    public Medication create(@Valid @RequestBody NewMedication req, @SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("insert into medications (recipient_id, name, dose, timing, caution, name_hidden, emergency_visible, "
                        + "ai_source, confirmed_at) values (?, ?, ?, nullif(?, ''), nullif(?, ''), ?, ?, 'manual', now()) "
                        + "returning " + COLUMNS)
                .params(Membership.of(db, me).recipientId(), req.name().trim(), req.dose().trim(), req.timing(), req.caution(),
                        Boolean.TRUE.equals(req.nameHidden()), !Boolean.FALSE.equals(req.emergencyVisible()))
                .query(Medication.class).single();
    }

    @Operation(summary = "약 고치기", description = "보낸 것만 바뀐다. 이름 숨기기(nameHidden)와 긴급조회 노출도 여기서. timing·caution은 빈 문자열을 보내면 지운다.")
    @PatchMapping("/{id}")
    public Medication update(@PathVariable UUID id, @Valid @RequestBody MedicationPatch req, @SessionAttribute(GUARDIAN) UUID me) {
        if (req.name() != null && req.name().isBlank()) throw new ResponseStatusException(BAD_REQUEST, "약 이름을 적어 주세요");
        if (req.dose() != null && req.dose().isBlank()) throw new ResponseStatusException(BAD_REQUEST, "용량은 꼭 확인해서 적어 주세요");
        return db.sql("update medications set name = coalesce(?, name), dose = coalesce(?, dose), "
                        + "timing = nullif(coalesce(?, timing), ''), caution = nullif(coalesce(?, caution), ''), "
                        + "name_hidden = coalesce(?::boolean, name_hidden), emergency_visible = coalesce(?::boolean, emergency_visible) "
                        + "where id = ? and recipient_id = ? returning " + COLUMNS)
                .params(trim(req.name()), trim(req.dose()), req.timing(), req.caution(), req.nameHidden(), req.emergencyVisible(),
                        id, Membership.of(db, me).recipientId())
                .query(Medication.class).optional().orElseThrow(MedicationController::notFound);
    }

    @Operation(summary = "약 지우기")
    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    public void delete(@PathVariable UUID id, @SessionAttribute(GUARDIAN) UUID me) {
        int n = db.sql("delete from medications where id = ? and recipient_id = ?")
                .params(id, Membership.of(db, me).recipientId()).update();
        if (n == 0) throw notFound();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(NOT_FOUND, "약을 찾을 수 없어요");
    }
}
