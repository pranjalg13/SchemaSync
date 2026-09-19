package com.schemasync.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    @DisplayName("a Neon-style URL keeps sslmode and drops libpq-only options")
    void neon() {
        Map<String, Object> p = DatabaseUrlEnvironmentPostProcessor.toSpringProperties(
                "postgresql://alice:s3cret@ep-cool-name-123.eu-central-1.aws.neon.tech/neondb"
                + "?sslmode=require&channel_binding=require");
        assertThat(p.get("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://ep-cool-name-123.eu-central-1.aws.neon.tech/neondb?sslmode=require");
        assertThat(p.get("spring.datasource.username")).isEqualTo("alice");
        assertThat(p.get("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("a Render internal URL has no port and no query")
    void renderInternal() {
        Map<String, Object> p = DatabaseUrlEnvironmentPostProcessor.toSpringProperties(
                "postgres://schemasync:pw@dpg-abc123-a/schemasync");
        assertThat(p.get("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc123-a/schemasync");
    }

    @Test
    @DisplayName("an explicit port is kept")
    void port() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.toSpringProperties(
                "postgres://u:p@db.example.com:6543/app").get("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.example.com:6543/app");
    }

    @Test
    @DisplayName("an encoded colon, slash and plus in the password survive intact")
    void encodedPassword() {
        // The failure this guards: decoding the user info BEFORE splitting it turns the encoded
        // colon into a real one, and the password is cut in half.
        Map<String, Object> p = DatabaseUrlEnvironmentPostProcessor.toSpringProperties(
                "postgres://bob:pa%3Ass%2Fw+rd@host/db");
        assertThat(p.get("spring.datasource.password")).isEqualTo("pa:ss/w+rd");
    }

    @Test
    @DisplayName("a non-Postgres URL is refused with a readable message")
    void wrongScheme() {
        assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.toSpringProperties("mysql://u:p@h/db"))
                .hasMessageContaining("postgres://");
    }
}
