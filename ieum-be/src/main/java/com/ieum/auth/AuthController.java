package com.ieum.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

/** 가입·로그인. 문자 인증은 모의(고정 코드, 실제 발송 없음). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String GUARDIAN = "guardianId";
    static final String VERIFIED_PHONE = "verifiedPhone";
    static final String MOCK_OTP = "000000";
    static final String PHONE = "^01[016789]-?\\d{3,4}-?\\d{4}$";

    record PhoneReq(@NotBlank @Pattern(regexp = PHONE, message = "휴대폰 번호 형식이 아니에요") String phone) {}

    record VerifyReq(@NotBlank @Pattern(regexp = PHONE, message = "휴대폰 번호 형식이 아니에요") String phone,
                     @NotBlank String code) {}

    record SignupReq(@NotBlank @Size(max = 50) String name, @NotNull @Past LocalDate birthDate) {}

    public record Me(UUID id, String name, String phone, LocalDate birthDate, UUID recipientId, String role) {}

    private final JdbcClient db;

    public AuthController(JdbcClient db) {
        this.db = db;
    }

    @PostMapping("/otp/request")
    public Map<String, Object> requestOtp(@Valid @RequestBody PhoneReq req) {
        return Map.of("sent", true, "mock", true, "hint", MOCK_OTP);
    }

    /** 인증 성공 → 가입된 번호면 바로 로그인, 아니면 signup 대기. */
    @PostMapping("/otp/verify")
    public Map<String, Object> verifyOtp(@Valid @RequestBody VerifyReq req, HttpServletRequest http) {
        if (!MOCK_OTP.equals(req.code())) throw new ResponseStatusException(BAD_REQUEST, "인증번호가 맞지 않아요");
        String phone = req.phone().replaceAll("\\D", "");
        HttpSession old = http.getSession(false);
        if (old != null) old.invalidate(); // 세션 고정 공격 방지 + 이전 로그인 정리
        HttpSession session = http.getSession(true);
        session.setAttribute(VERIFIED_PHONE, phone);
        var id = db.sql("select id from guardians where phone = ?").param(phone).query(UUID.class).optional();
        id.ifPresent(g -> session.setAttribute(GUARDIAN, g));
        return Map.of("registered", id.isPresent());
    }

    @PostMapping("/signup")
    public Me signup(@Valid @RequestBody SignupReq req, HttpSession session,
                     @SessionAttribute(name = VERIFIED_PHONE, required = false) String phone) {
        if (phone == null) throw new ResponseStatusException(UNAUTHORIZED, "전화번호 인증을 먼저 해 주세요");
        UUID id = db.sql("insert into guardians (name, phone, birth_date) values (?, ?, ?) returning id")
                .params(req.name(), phone, req.birthDate()).query(UUID.class).single();
        session.setAttribute(GUARDIAN, id);
        return me(id);
    }

    @GetMapping("/me")
    public Me me(@SessionAttribute(name = GUARDIAN, required = false) UUID id) {
        if (id == null) throw new ResponseStatusException(UNAUTHORIZED, "로그인이 필요해요");
        return db.sql("""
                        select g.id, g.name, g.phone, g.birth_date, rg.recipient_id, rg.role
                        from guardians g left join recipient_guardians rg on rg.guardian_id = g.id
                        where g.id = ?""")
                .param(id).query(Me.class).optional()
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "로그인이 필요해요"));
    }

    @PostMapping("/logout")
    @ResponseStatus(NO_CONTENT)
    public void logout(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        if (session != null) session.invalidate();
    }
}
