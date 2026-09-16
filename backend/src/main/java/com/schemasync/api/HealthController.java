package com.schemasync.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
class HealthController {

    private final JdbcTemplate jdbc;
    private final String mainSchema;

    HealthController(JdbcTemplate jdbc,
                     @org.springframework.beans.factory.annotation.Value("${schemasync.main-schema}") String mainSchema) {
        this.jdbc = jdbc;
        this.mainSchema = mainSchema;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        Integer branches = jdbc.queryForObject("SELECT count(*)::int FROM sv.branch", Integer.class);
        return Map.of(
                "status", "ok",
                "mainSchema", mainSchema,
                "branches", branches == null ? 0 : branches);
    }
}
