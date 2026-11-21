package com.ieum;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcClient db;

    public HealthController(JdbcClient db) {
        this.db = db;
    }

    /** DB까지 찔러서 Supabase 7일 비활성 정지도 함께 막는다. */
    @GetMapping("/health")
    public String health() {
        db.sql("select 1").query(Integer.class).single();
        return "ok";
    }
}
