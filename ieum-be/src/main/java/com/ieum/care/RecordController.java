package com.ieum.care;

import com.ieum.ApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.ieum.auth.AuthController.GUARDIAN;
import static org.springframework.http.HttpStatus.*;

/** 기록(알러지·특이사항 등) 관리. 새 기록 만들기(말·글 → 가림 → AI 정리 → 확인)는 AI 단계에서 붙는다. */
@Tag(name = ApiDocs.CARE)
@RestController
@RequestMapping("/api/records")
public class RecordController {

    record CareRecord(UUID id, String category, String summary, String quote,
                      @Schema(allowableValues = {"pass", "review", "blocked"}) String verifyStatus,
                      boolean emergencyVisible, OffsetDateTime confirmedAt, OffsetDateTime lastCheckedAt,
                      @Schema(description = "마지막 확인이 30일을 넘음 → 배지") boolean stale) {}

    record RecordPatch(@Size(max = 500) String summary, Boolean emergencyVisible) {}

    /** 마지막 확인일이 없으면 확정일부터 센다. */
    private static final String COLUMNS = """
            id, category, summary, quote_text as quote, verify_status, emergency_visible, confirmed_at, last_checked_at,
            coalesce(coalesce(last_checked_at, confirmed_at) < now() - interval '30 days', false) as stale""";

    private final JdbcClient db;

    public RecordController(JdbcClient db) {
        this.db = db;
    }

    @Operation(summary = "기록 목록", description = "확인 전(confirmedAt=null)과 확인된 기록 모두. stale=true면 30일 지남 배지.")
    @GetMapping
    public List<CareRecord> list(@SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("select " + COLUMNS + " from care_records where recipient_id = ? order by created_at")
                .param(Membership.of(db, me).recipientId()).query(CareRecord.class).list();
    }

    @Operation(summary = "기록 고치기·긴급조회 노출 바꾸기", description = "보낸 것만 바뀐다. 고치면 마지막 확인일도 오늘이 된다. 확인된 기록만.")
    @PatchMapping("/{id}")
    public CareRecord update(@PathVariable UUID id, @Valid @RequestBody RecordPatch req, @SessionAttribute(GUARDIAN) UUID me) {
        if (req.summary() != null && req.summary().isBlank()) throw new ResponseStatusException(BAD_REQUEST, "내용을 적어 주세요");
        return db.sql("update care_records set summary = coalesce(?, summary), "
                        + "emergency_visible = coalesce(?::boolean, emergency_visible), last_checked_at = now() "
                        + "where id = ? and recipient_id = ? and confirmed_at is not null returning " + COLUMNS)
                .params(req.summary(), req.emergencyVisible(), id, Membership.of(db, me).recipientId())
                .query(CareRecord.class).optional().orElseThrow(RecordController::notFound);
    }

    @Operation(summary = "다시 확인 (내용 그대로 맞아요)", description = "마지막 확인일을 오늘로 바꿔 30일 배지를 없앤다.")
    @PostMapping("/{id}/check")
    public CareRecord check(@PathVariable UUID id, @SessionAttribute(GUARDIAN) UUID me) {
        return db.sql("update care_records set last_checked_at = now() "
                        + "where id = ? and recipient_id = ? and confirmed_at is not null returning " + COLUMNS)
                .params(id, Membership.of(db, me).recipientId())
                .query(CareRecord.class).optional().orElseThrow(RecordController::notFound);
    }

    @Operation(summary = "기록 지우기")
    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    public void delete(@PathVariable UUID id, @SessionAttribute(GUARDIAN) UUID me) {
        int n = db.sql("delete from care_records where id = ? and recipient_id = ?")
                .params(id, Membership.of(db, me).recipientId()).update();
        if (n == 0) throw notFound();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(NOT_FOUND, "기록을 찾을 수 없어요");
    }
}
