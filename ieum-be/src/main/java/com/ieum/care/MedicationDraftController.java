package com.ieum.care;

import com.ieum.ApiDocs;
import com.ieum.ai.Claude;
import com.ieum.drug.DrugCatalog;
import com.ieum.safety.IpHasher;
import com.ieum.verify.QuoteVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static com.ieum.demo.DemoController.DEMO;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 약 등록 (docs/이음수첩_AI_개인정보_약등록.md 3.2):
 * 사진 → OCR → 자동 가림 → 가림 확인 → Opus 약 목록 정리 → 식약처 대조 → 보호자 확정.
 * ponytail: 사진 OCR·Presidio(Python 컨테이너)가 붙기 전이라 샘플 복약안내문의 가린 OCR 줄로 시작한다
 */
@Tag(name = ApiDocs.CARE)
@RestController
@RequestMapping("/api/medications")
public class MedicationDraftController {

    /** 샘플 복약안내문을 OCR → Presidio로 가린 결과. uncertain=인식 신뢰도가 낮은 줄. */
    record OcrLine(String text, boolean uncertain) {}

    static final List<OcrLine> SAMPLE_LINES = List.of(
            new OcrLine("복약안내문  환자 [이름]  주민번호 [주민번호]", false),
            new OcrLine("질병분류기호 [질병코드]  조제일 2026-11-21", false),
            new OcrLine("와파린정2mg  1일 1회  아침 식후  1정  7일", false),
            new OcrLine("도네페질정5mg  1일 1회  취침 전  1정  7일", false),
            new OcrLine("아모디핀정5mg  1일 1회  아침 식후  0.5정  7일", true),
            new OcrLine("○○약국  [전화번호]", false));

    private static final String SYSTEM = """
            너는 복약안내문을 읽은 OCR 줄을 약 목록으로 정리한다. 개인정보는 이미 가려져 있다.
            - name: 약 이름과 함량 (예: 와파린정 2mg)
            - dose: 한 번에 먹는 양 (예: 1정, 0.5정). OCR에 있는 숫자를 그대로 쓴다
            - timing: 복용 시간을 쉬운 말로 (예: 하루 1번 아침 식후)
            - easyText: 당사자에게 보여 줄 쉬운 말. 약 이름은 쓰지 않는다 (예: 아침밥 먹고 약 먹기)
            - quote: 그 약의 근거가 된 OCR 줄을 그대로 옮긴다
            약을 바꾸거나 끊으라는 판단·권고는 하지 않는다.""";

    public record OrganizedMeds(List<Med> medications) {}

    public record Med(String name, String dose, String timing, String easyText, String quote) {}

    private static final OrganizedMeds SAVED = new OrganizedMeds(List.of(
            new Med("와파린정 2mg", "1정", "하루 1번 아침 식후", "아침밥 먹고 약 먹기", "와파린정2mg  1일 1회  아침 식후  1정  7일"),
            new Med("도네페질정 5mg", "1정", "하루 1번 자기 전", "자기 전에 약 먹기", "도네페질정5mg  1일 1회  취침 전  1정  7일"),
            new Med("아모디핀정 5mg", "0.5정", "하루 1번 아침 식후", "아침밥 먹고 약 먹기", "아모디핀정5mg  1일 1회  아침 식후  0.5정  7일")));

    record SourceResult(UUID sourceId, List<OcrLine> lines, List<MedDraft> medications,
                        @Schema(allowableValues = {"ai", "saved"}) String source) {}

    record MedDraft(UUID id, String name, String dose, String timing, String easyText, String caution,
                    @Schema(description = "식약처 허가 품목명 (대조 결과)") String matchedItemName,
                    @Schema(description = "흐린 용량 → '확인 필요'. 보호자가 고친 뒤 확정") boolean doseNeedsCheck,
                    @Schema(allowableValues = {"pass", "review", "blocked"}) String verifyStatus, String reason) {}

    record ConfirmReq(@Size(max = 100) String name, @Size(max = 100) String dose, @Size(max = 100) String timing,
                      Boolean nameHidden, Boolean emergencyVisible) {}

    private final JdbcClient db;
    private final Claude claude;
    private final IpHasher ips;

    public MedicationDraftController(JdbcClient db, Claude claude, IpHasher ips) {
        this.db = db;
        this.claude = claude;
        this.ips = ips;
    }

    @Operation(summary = "샘플 복약안내문으로 약 등록 (체험)", description = """
            샘플 사진을 OCR·자동 가림한 줄 → Opus 약 목록 정리 → 식약처 10종과 대조해 주의 약 종류를 붙이고 임시 저장한다.
            흐린 용량은 doseNeedsCheck=true. 보호자가 POST /api/medications/{id}/confirm으로 확정한다.""")
    @PostMapping("/sources/sample")
    @Transactional
    public SourceResult sample(@SessionAttribute(GUARDIAN) UUID me, @SessionAttribute(name = DEMO, required = false) UUID demoId,
                               HttpServletRequest http) {
        UUID recipientId = Membership.of(db, me).recipientId();
        String ocrText = String.join("\n", SAMPLE_LINES.stream().map(OcrLine::text).toList());

        OrganizedMeds org = null;
        String source = "ai";
        boolean aiAgreed = db.sql("select exists (select 1 from consents where recipient_id = ? and item = 'OVERSEAS_AI' and agreed)")
                .param(recipientId).query(Boolean.class).single();
        if (aiAgreed) org = claude.ask(SYSTEM, ocrText, OrganizedMeds.class, "med_draft", demoId, ips.hash(http)).orElse(null);
        if (org == null || org.medications() == null) { org = SAVED; source = "saved"; }

        UUID sourceId = db.sql("insert into medication_sources (recipient_id) values (?) returning id")
                .param(recipientId).query(UUID.class).single();
        List<MedDraft> drafts = new ArrayList<>();
        for (Med m : org.medications()) {
            var r = QuoteVerifier.verify(ocrText, m.name() + " " + m.dose(), m.quote());
            boolean unclear = SAMPLE_LINES.stream().anyMatch(l -> l.uncertain() && m.quote() != null
                    && QuoteVerifier.verify(l.text(), "", m.quote()).status() != QuoteVerifier.Status.blocked);
            var drug = DrugCatalog.find(m.name());
            UUID id = db.sql("""
                            insert into medications (recipient_id, source_id, drug_code, name, dose, timing, easy_text, quote_text,
                                                     verify_status, ai_source, caution, dose_needs_check, emergency_visible)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ocr', ?, ?, true) returning id""")
                    .params(recipientId, sourceId, drug.map(DrugCatalog.Drug::itemSeq).orElse(null), m.name(), m.dose(), m.timing(),
                            m.easyText(), m.quote(), r.status().name(), drug.map(DrugCatalog.Drug::caution).orElse(null), unclear)
                    .query(UUID.class).single();
            drafts.add(new MedDraft(id, m.name(), m.dose(), m.timing(), m.easyText(), drug.map(DrugCatalog.Drug::caution).orElse(null),
                    drug.map(DrugCatalog.Drug::itemName).orElse(null), unclear, r.status().name(), r.reason()));
        }
        return new SourceResult(sourceId, SAMPLE_LINES, drafts, source);
    }

    @Operation(summary = "약 확정 (맞아요, 저장)", description = "임시 약을 확정한다. 흐린 용량(doseNeedsCheck)은 dose를 확인해 보내야 확정된다. blocked는 확정할 수 없다.")
    @PostMapping("/{id}/confirm")
    public MedicationController.Medication confirm(@PathVariable UUID id, @Valid @RequestBody(required = false) ConfirmReq req,
                                                   @SessionAttribute(GUARDIAN) UUID me) {
        UUID recipientId = Membership.of(db, me).recipientId();
        record Pending(String verifyStatus, boolean doseNeedsCheck) {}
        Pending p = db.sql("select verify_status, dose_needs_check from medications where id = ? and recipient_id = ? and confirmed_at is null")
                .params(id, recipientId).query(Pending.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "확인할 약이 없어요"));
        if ("blocked".equals(p.verifyStatus())) throw new ResponseStatusException(CONFLICT, "원문에 없는 약이라 저장할 수 없어요");
        String dose = req == null || req.dose() == null || req.dose().isBlank() ? null : req.dose().strip();
        if (p.doseNeedsCheck() && dose == null)
            throw new ResponseStatusException(CONFLICT, "흐리게 읽힌 용량이에요. 약봉투를 보고 용량을 확인해 적어 주세요");
        return db.sql("""
                        update medications set confirmed_at = now(), dose_needs_check = false, dose = coalesce(?, dose),
                               name = coalesce(?, name), timing = coalesce(?, timing),
                               name_hidden = coalesce(?::boolean, name_hidden), emergency_visible = coalesce(?::boolean, emergency_visible)
                        where id = ? and recipient_id = ?
                        returning id, name, dose, timing, caution, name_hidden, emergency_visible, confirmed_at""")
                .params(dose, req == null ? null : blankToNull(req.name()), req == null ? null : blankToNull(req.timing()),
                        req == null ? null : req.nameHidden(), req == null ? null : req.emergencyVisible(), id, recipientId)
                .query(MedicationController.Medication.class).single();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
