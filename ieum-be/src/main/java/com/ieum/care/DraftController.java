package com.ieum.care;

import com.ieum.ApiDocs;
import com.ieum.ai.Claude;
import com.ieum.safety.IpHasher;
import com.ieum.safety.PiiFilter;
import com.ieum.verify.QuoteVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 정보 등록: 말·글 → 개인정보 가림 → Opus 정리 + 원문 인용 → 규칙 검증 → 임시 저장 → 보호자 확인.
 * 키가 없거나 한도를 넘으면 저장된 응답(샘플 문장) 또는 문장 그대로 저장으로 대체한다.
 */
@Tag(name = ApiDocs.CARE)
@RestController
@RequestMapping("/api/records")
public class DraftController {

    /** 체험 "샘플 음성 변환해 보기"의 받아쓴 글. 실제 음성 업로드(Whisper)는 이번 범위에서 뺐다. */
    static final String SAMPLE_TRANSCRIPT = "어머니는 페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요. "
            + "불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요. "
            + "저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요. 자몽 주스는 드시면 안 돼요.";

    private static final String SYSTEM = """
            너는 치매 가족 돌봄 기록을 정리한다. 보호자가 남긴 글을 항목별로 나눈다.
            - category: 알러지, 복약, 진정법, 일과, 특이사항, 연락 중 하나
            - summary: 보호자가 한 말을 쉬운 말 한 문장으로. 원문에 없는 내용·판단·권고(약을 바꾸라 등)를 더하지 않는다
            - quote: 그 항목의 근거가 되는 원문 문장을 한 글자도 고치지 말고 그대로 옮긴다
            - followUps: 돌봄 인계에 꼭 필요한데 빠진 정보를 묻는 짧은 질문 0~3개
            [이름], [전화번호] 같은 표시는 가려 둔 개인정보이니 그대로 둔다.""";

    public record Organized(List<Item> items, List<String> followUps) {}

    public record Item(String category, String summary, String quote) {}

    record DraftReq(@NotBlank @Size(max = 1000) String text,
                    @Schema(description = "체험: 원문에 없는 약을 끼워 넣은 AI 실수를 섞어 차단되는지 보여 준다") Boolean injectMistake) {}

    record Draft(UUID id, String category, String summary, String quote,
                 @Schema(allowableValues = {"pass", "review", "blocked"}) String verifyStatus, String reason) {}

    record DraftResult(List<Draft> items, List<String> followUps,
                       @Schema(allowableValues = {"ai", "saved", "rule"}, description = "ai: Opus 정리, saved: 저장된 응답, rule: AI 없이 문장 그대로")
                       String source) {}

    record Transcript(String text, @Schema(allowableValues = {"whisper", "saved"}) String source) {}

    record ConfirmReq(@Size(max = 500) String summary, Boolean emergencyVisible) {}

    private static final Organized SAVED = new Organized(List.of(
            new Item("알러지", "페니실린 주사를 맞으면 온몸에 두드러기가 나요", "페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요"),
            new Item("진정법", "불안해하시면 손을 잡고 고향의 봄을 불러 드리면 가라앉아요", "불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요"),
            new Item("특이사항", "저녁 5시쯤 집에 가야 한다며 나가려고 하세요", "저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요"),
            new Item("복약", "자몽 주스는 드시면 안 돼요", "자몽 주스는 드시면 안 돼요")),
            List.of("약은 하루에 몇 번, 언제 드시나요?", "밤에 깨시면 어떻게 해 드리나요?"));

    /** 체험 "AI 실수 끼워 넣기": 원문에 없는 약. 검증기가 막아야 한다. */
    private static final Item MISTAKE = new Item("복약", "아스피린을 하루 두 번 드세요", "아스피린은 하루 두 번 꼭 드세요");

    private final JdbcClient db;
    private final Claude claude;
    private final IpHasher ips;

    public DraftController(JdbcClient db, Claude claude, IpHasher ips) {
        this.db = db;
        this.claude = claude;
        this.ips = ips;
    }

    @Operation(summary = "샘플 음성 받아쓰기 (체험)", description = "서버에 있는 샘플 음성의 받아쓴 글. 보호자가 고친 뒤 draft로 보낸다.")
    @PostMapping("/transcribe/sample")
    public Transcript sampleTranscript() {
        return new Transcript(SAMPLE_TRANSCRIPT, "saved");
    }

    @Operation(summary = "기록 정리 (AI)", description = """
            글(최대 1000자)을 개인정보 가림 → Opus 정리·원문 인용 → 규칙 검증 → 임시 저장한다.
            verifyStatus: pass 통과 / review 확인 필요 / blocked 저장 막음. 보호자가 confirm해야 확정된다.
            해외 AI 전송에 동의하지 않았으면 AI 없이 문장 그대로 나눈다(source=rule).""")
    @PostMapping("/draft")
    @Transactional
    public DraftResult draft(@Valid @RequestBody DraftReq req, @SessionAttribute(GUARDIAN) UUID me,
                             @SessionAttribute(name = DEMO, required = false) UUID demoId, HttpServletRequest http) {
        UUID recipientId = Membership.of(db, me).recipientId();
        String masked = mask(req.text(), recipientId);

        Organized org = null;
        String source = "ai";
        if (overseasAiAgreed(recipientId))
            org = claude.ask(SYSTEM, masked, Organized.class, "record_draft", demoId, ips.hash(http)).orElse(null);
        if (org == null && req.text().strip().equals(SAMPLE_TRANSCRIPT)) { org = SAVED; source = "saved"; }
        if (org == null) { org = bySentence(masked); source = "rule"; }

        List<Item> items = new ArrayList<>(org.items() == null ? List.of() : org.items());
        if (Boolean.TRUE.equals(req.injectMistake())) items.add(MISTAKE);

        List<Draft> drafts = new ArrayList<>();
        for (Item it : items) {
            var r = QuoteVerifier.verify(masked, it.summary(), it.quote());
            UUID id = db.sql("""
                            insert into care_records (recipient_id, category, summary, quote_text, verify_status)
                            values (?, ?, ?, ?, ?) returning id""")
                    .params(recipientId, it.category() == null ? "기타" : it.category(), it.summary(), it.quote(), r.status().name())
                    .query(UUID.class).single();
            drafts.add(new Draft(id, it.category(), it.summary(), it.quote(), r.status().name(), r.reason()));
        }
        return new DraftResult(drafts, org.followUps() == null ? List.of() : org.followUps(), source);
    }

    @Operation(summary = "기록 확인 (맞아요, 저장)", description = "임시 기록을 확정한다. blocked는 확정할 수 없다. summary를 보내면 보호자 말로 고쳐 저장한다.")
    @PostMapping("/{id}/confirm")
    public RecordController.CareRecord confirm(@PathVariable UUID id, @Valid @RequestBody(required = false) ConfirmReq req,
                                               @SessionAttribute(GUARDIAN) UUID me) {
        UUID recipientId = Membership.of(db, me).recipientId();
        String status = db.sql("select verify_status from care_records where id = ? and recipient_id = ? and confirmed_at is null")
                .params(id, recipientId).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "확인할 기록이 없어요"));
        if ("blocked".equals(status)) throw new ResponseStatusException(CONFLICT, "원문에 없는 말이라 저장할 수 없어요");
        String summary = req == null || req.summary() == null || req.summary().isBlank() ? null : req.summary().strip();
        Boolean visible = req == null ? null : req.emergencyVisible();
        return db.sql("""
                        update care_records set confirmed_at = now(), last_checked_at = now(), summary = coalesce(?, summary),
                               emergency_visible = coalesce(?::boolean, emergency_visible)
                        where id = ? and recipient_id = ? returning id, category, summary, quote_text as quote, verify_status,
                               emergency_visible, confirmed_at, last_checked_at, false as stale""")
                .params(summary, visible, id, recipientId).query(RecordController.CareRecord.class).single();
    }

    /** PII 필터 + 가족 이름 가림. ponytail: Presidio(Python 컨테이너)가 붙기 전까지의 정규식판 */
    String mask(String text, UUID recipientId) {
        String t = PiiFilter.strip(text);
        List<String> names = db.sql("""
                        select name from guardians where id in (select guardian_id from recipient_guardians where recipient_id = ?)
                        union select name from care_recipients where id = ?""")
                .params(recipientId, recipientId).query(String.class).list();
        for (String n : names) if (n != null && n.length() >= 2) t = t.replace(n, "[이름]");
        return t;
    }

    boolean overseasAiAgreed(UUID recipientId) {
        return db.sql("select exists (select 1 from consents where recipient_id = ? and item = 'OVERSEAS_AI' and agreed)")
                .param(recipientId).query(Boolean.class).single();
    }

    /** AI 없이: 문장마다 그대로 한 항목 (인용 = 원문 문장이라 검증은 통과한다). */
    static Organized bySentence(String text) {
        List<Item> items = new ArrayList<>();
        for (String s : text.split("(?<=[.!?。])\\s+|\\n+")) {
            if (!s.isBlank()) items.add(new Item("기타", s.strip(), s.strip()));
        }
        return new Organized(items, List.of());
    }
}
