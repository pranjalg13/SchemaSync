package com.schemasync.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts a {@code DATABASE_URL} of the form {@code postgres://user:pass@host:port/db}.
 *
 * <p>Every managed Postgres host (Render, Neon, Supabase, Railway) hands out that URI shape, while
 * the JDBC driver wants {@code jdbc:postgresql://host:port/db} plus separate credentials. Without
 * this, deploying means hand-splitting a connection string into three variables -- the kind of
 * step that is easy to get subtly wrong (an unencoded character in a password) and hard to debug
 * from a hosting dashboard.
 *
 * <p>Explicit {@code SCHEMASYNC_DB_URL} always wins, so local and Compose setups are untouched.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String url = env.getProperty("DATABASE_URL");
        if (url == null || url.isBlank() || env.containsProperty("SCHEMASYNC_DB_URL")) {
            return;
        }
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", toSpringProperties(url)));
    }

    static Map<String, Object> toSpringProperties(String databaseUrl) {
        URI uri = URI.create(databaseUrl.trim());
        String scheme = uri.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must start with postgres:// or postgresql://, got " + scheme + "://");
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());

        // Only sslmode is carried over. Hosted URLs also include libpq-only options such as
        // channel_binding, which the JDBC driver does not understand under that name.
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("sslmode=")) {
                    jdbc.append("?").append(pair);
                }
            }
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.datasource.url", jdbc.toString());

        // Split the RAW user info on the first colon, then decode each half. Decoding first would
        // turn an encoded ':' in the password into a real one and split it in the wrong place.
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            props.put("spring.datasource.username", decode(user));
            if (colon >= 0) {
                props.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
            }
        }
        return props;
    }

    /** URL-decodes without URLDecoder's form-encoding rule that turns '+' into a space. */
    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
