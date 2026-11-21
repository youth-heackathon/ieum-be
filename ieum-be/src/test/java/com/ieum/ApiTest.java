package com.ieum;

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

    static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(UTF_8), jsonPath);
    }

    long count(String fromWhere, Object... params) {
        return db.sql("select count(*) from " + fromWhere).params(params).query(Long.class).single();
    }
}
