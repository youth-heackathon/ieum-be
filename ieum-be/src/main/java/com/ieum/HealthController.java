package com.ieum;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiDocs.COMMON)
@RestController
public class HealthController {

    private final JdbcClient db;

    public HealthController(JdbcClient db) {
        this.db = db;
    }

    @Operation(summary = "헬스체크", description = "DB까지 확인한다. Railway 헬스체크 경로로 쓴다.")
    @GetMapping("/health")
    public String health() {
        db.sql("select 1").query(Integer.class).single();
        return "ok";
    }
}
