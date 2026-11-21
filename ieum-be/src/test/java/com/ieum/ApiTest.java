package com.ieum;

import com.ieum.safety.IpHasher;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API 시나리오 테스트 공통. 모든 테스트가 같은 컨텍스트·같은 Postgres 컨테이너를 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
abstract class ApiTest {

    private static final AtomicInteger PHONES = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient db;
    @Autowired
    protected IpHasher ips;

    /** DB를 같이 쓰므로 테스트마다 겹치지 않는 번호. */
    static String uniquePhone() {
        return "010%08d".formatted(PHONES.incrementAndGet());
    }

    TestUser anonymous() {
        return new TestUser(mvc);
    }

    TestUser guardian(String name) throws Exception {
        return new TestUser(mvc).signup(uniquePhone(), name);
    }

    /** 보호대상자까지 등록한 핵심 보호자. */
    TestUser primary(String name) throws Exception {
        TestUser u = guardian(name);
        u.post("/api/recipients", "{\"name\":\"어머니\"}").andExpect(status().isCreated());
        return u;
    }

    /** primary의 초대코드로 연결된 일반 보호자. */
    TestUser member(TestUser primary, String name) throws Exception {
        String code = read(primary.post("/api/guardians/invite-codes", ""), "$.code");
        TestUser u = guardian(name);
        u.post("/api/guardians/invite-codes/redeem", "{\"code\":\"%s\",\"agreed\":true}".formatted(code))
                .andExpect(status().isOk());
        return u;
    }

    /** 체험을 시작한 심사위원 = 동의·기록·약·QR이 다 갖춰진 가구의 보호자. */
    TestUser judge() throws Exception {
        TestUser u = anonymous();
        u.post("/api/demo/sessions", "").andExpect(status().isCreated());
        return u;
    }

    /** 보호자의 현재 QR 토큰 (없으면 새로 발급). */
    String qrToken(TestUser guardian) throws Exception {
        var current = guardian.get("/api/access-codes").andReturn().getResponse();
        if (current.getStatus() == 200) return JsonPath.read(current.getContentAsString(UTF_8), "$.qrToken");
        return read(guardian.post("/api/access-codes", "").andExpect(status().isCreated()), "$.qrToken");
    }

    java.util.UUID recipientOf(TestUser guardian) throws Exception {
        return java.util.UUID.fromString(read(guardian.get("/api/auth/me"), "$.recipientId"));
    }

    static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(UTF_8), jsonPath);
    }

    long count(String fromWhere, Object... params) {
        return db.sql("select count(*) from " + fromWhere).params(params).query(Long.class).single();
    }
}
