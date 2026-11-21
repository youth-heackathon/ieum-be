package com.ieum.safety;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AI 호출 한도 (FR-11.3): 체험 세션당 5회, IP당 하루 20회, 전체 하루 300회. 입력은 500자까지.
 * 쓰는 쪽: allows()가 false면 AI를 부르지 말고 저장된 응답으로 대체 → 실제로 부른 뒤 record().
 */
@Component
public class AiQuota {

    public static final int MAX_INPUT_CHARS = 500;
    static final int PER_SESSION = 5, PER_IP_DAY = 20, GLOBAL_DAY = 300;

    private record Counts(long sessionCount, long ipCount, long total) {}

    private final JdbcClient db;

    public AiQuota(JdbcClient db) {
        this.db = db;
    }

    /** demoSessionId는 실제 보호자면 null (세션 한도 없음). */
    // ponytail: 확인과 기록 사이에 동시 요청이 끼면 몇 건 넘을 수 있다. 정확히 막아야 하면 advisory lock
    public boolean allows(UUID demoSessionId, String ipHash) {
        Counts c = db.sql("""
                        select count(*) filter (where session_id = :session) as session_count,
                               count(*) filter (where ip_hash = :ip)         as ip_count,
                               count(*)                                      as total
                        from ai_calls where created_at > now() - interval '1 day'""")
                .param("session", demoSessionId).param("ip", ipHash)
                .query(Counts.class).single();
        return c.sessionCount() < PER_SESSION && c.ipCount() < PER_IP_DAY && c.total() < GLOBAL_DAY;
    }

    /** 원문은 받지도 저장하지도 않는다 (FR-11.2·11.4). */
    public void record(UUID demoSessionId, String ipHash, String kind, String model, String status) {
        db.sql("insert into ai_calls (session_id, ip_hash, kind, model, status) values (?, ?, ?, ?, ?)")
                .params(demoSessionId, ipHash, kind, model, status).update();
    }
}
