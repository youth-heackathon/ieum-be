package com.ieum;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 문서. 돌릴 때마다 docs/openapi.json을 다시 만든다 → 바뀌었으면 같이 커밋 (CI가 확인한다).
 * 프론트: npx openapi-typescript docs/openapi.json -o src/api.d.ts
 */
@DisplayName("API 문서")
class ApiDocsTest extends ApiTest {

    static final Path SPEC = Path.of("docs/openapi.json");

    String spec() throws Exception {
        return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("docs/openapi.json을 코드와 같게 만든다")
    void writeSpec() throws Exception {
        String spec = spec() + "\n";
        if (!Files.exists(SPEC) || !Files.readString(SPEC, UTF_8).equals(spec)) Files.writeString(SPEC, spec, UTF_8);
    }

    @Test
    @DisplayName("모든 API에 한 줄 설명과 분류(태그)가 있다")
    void everyOperationDocumented() throws Exception {
        String spec = spec();
        Map<String, Map<String, Map<String, Object>>> paths = JsonPath.read(spec, "$.paths");
        paths.forEach((path, ops) -> ops.forEach((method, op) -> {
            assertNotNull(op.get("summary"), method + " " + path + "에 @Operation(summary) 필요");
            assertNotNull(op.get("tags"), method + " " + path + "에 @Tag 필요");
        }));
        assertTrue(paths.containsKey("/api/public/access/{token}/emergency"));
        assertTrue(paths.containsKey("/api/demo/sessions"));
    }

    @Test
    @DisplayName("세션 값(로그인한 보호자·체험 세션)은 요청 파라미터로 문서에 나오지 않는다")
    void sessionAttributesHidden() throws Exception {
        List<String> names = JsonPath.read(spec(), "$.paths.*.*.parameters[*].name");
        assertFalse(names.contains("me"), names.toString());     // 로그인한 보호자
        assertFalse(names.contains("phone"), names.toString());  // 인증한 번호 (signup)
        assertTrue(List.of("id", "token", "viewToken").containsAll(names), "경로 변수만 있어야 한다: " + names);
    }

    @Test
    @DisplayName("Swagger UI가 열린다")
    void swaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
