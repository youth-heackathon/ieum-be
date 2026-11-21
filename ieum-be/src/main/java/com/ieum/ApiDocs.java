package com.ieum;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.List;

/** Swagger UI(/swagger-ui.html) 첫 화면 안내와 태그 순서. 각 API 설명은 컨트롤러의 @Operation에 있다. */
@Configuration
public class ApiDocs {

    static {
        // @SessionAttribute(로그인한 보호자·인증한 번호·체험 세션)는 서버가 쿠키 세션에서 꺼내는 값이라 요청 파라미터가 아니다
        SpringDocUtils.getConfig().addAnnotationsToIgnore(SessionAttribute.class);
    }

    public static final String COMMON = "공통";
    public static final String AUTH = "가입·로그인";
    public static final String RECIPIENT = "보호대상자·동의";
    public static final String LINK = "보호자 연결";
    public static final String HOME = "홈 (보호자)";
    public static final String CARE = "기록·약 관리 (보호자)";
    public static final String GUARDIAN_ACCESS = "QR·열람 기록·상세조회 동의·알림 (보호자)";
    public static final String PUBLIC = "조회하는 쪽 (로그인 없음)";
    public static final String DEMO = "체험 모드";

    private static final String GUIDE = """
            이음수첩 백엔드 API. 사람이 읽는 오류 문장은 모두 `detail`에 한국어로 온다.

            ## 로그인 (세션 쿠키)
            1. `POST /api/auth/otp/verify` — 인증번호는 모의라서 항상 `000000`
            2. 처음 쓰는 번호면 `POST /api/auth/signup`
            3. 이후 요청에 세션 쿠키(`SESSION`)를 실어 보내면 로그인 상태. Swagger에서 그대로 호출해도 쿠키가 붙는다. 세션은 DB에 저장돼 재배포해도 유지된다

            로그인 없이 쓰는 것: `/api/auth/**`, `/api/public/**`, `POST /api/demo/sessions`, `/health`

            ## 오류 형식 (ProblemDetail)
            ```json
            {"status": 409, "title": "Conflict", "detail": "이미 처리됐거나 10분이 지난 요청이에요"}
            ```
            400 입력 오류 · 401 로그인 필요 · 403 권한·동의 없음 · 404 없음 · 409 상태 충돌 · 429 횟수 제한

            ## 흐름
            - **긴급조회 (의료진)**: QR이면 `/a/{qrToken}` 그대로, 8자리면 `resolve`로 토큰을 받고 → `emergency`
            - **상세조회 (간병인)**: `detail-requests` 요청 → 보호자가 `approve` (10분 안) → 요청자가 상태를 2초마다 폴링하다가 `viewToken`을 받으면 → `detail/{viewToken}` (60분)
            - **체험**: `POST /api/demo/sessions` → 이후 보호자 API가 그대로 동작. 노트북은 `GET /api/home` 하나만 폴링(단계는 `demo.step`), QR은 `GET /api/access-codes`, 폰은 `GET /api/public/access/{token}/demo`로 단계를 따라간다
            """;

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("이음수첩 API").version("v1").description(GUIDE))
                .servers(List.of(new Server().url("/"))) // 같은 출처 기준 (Vercel rewrites로 /api 프록시)
                .tags(List.of(COMMON, AUTH, RECIPIENT, LINK, HOME, CARE, GUARDIAN_ACCESS, PUBLIC, DEMO).stream()
                        .map(name -> new Tag().name(name)).toList());
    }
}
