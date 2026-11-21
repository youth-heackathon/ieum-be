package com.ieum.care;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** 보호자가 연결된 보호대상자와 역할. [가정] 보호자 1명당 보호대상자 1명. */
public record Membership(UUID recipientId, String role) {

    public static Optional<Membership> find(JdbcClient db, UUID guardianId) {
        return db.sql("select recipient_id, role from recipient_guardians where guardian_id = ?")
                .param(guardianId).query(Membership.class).optional();
    }

    public static Membership of(JdbcClient db, UUID guardianId) {
        return find(db, guardianId).orElseThrow(() ->
                new ResponseStatusException(NOT_FOUND, "아직 연결된 보호대상자가 없어요"));
    }

    public boolean isPrimary() {
        return "primary".equals(role);
    }
}
