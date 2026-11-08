package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

public class PortResolutionTest {

    @Test
    public void testPortPropertyResolutionWithPortEnv() {
        StandardEnvironment env = new StandardEnvironment();
        // Simulate Render injecting PORT=10000
        env.getPropertySources().addFirst(new MapPropertySource("renderEnv", Map.of("PORT", "10000")));

        String template = "${PORT:${SERVER_PORT:8080}}";
        String resolved = env.resolvePlaceholders(template);
        assertEquals("10000", resolved, "Should resolve to Render's PORT when present");
    }

    @Test
    public void testPortPropertyResolutionWithServerPortEnv() {
        StandardEnvironment env = new StandardEnvironment();
        // Simulate SERVER_PORT=9090
        env.getPropertySources().addFirst(new MapPropertySource("customEnv", Map.of("SERVER_PORT", "9090")));

        String template = "${PORT:${SERVER_PORT:8080}}";
        String resolved = env.resolvePlaceholders(template);
        assertEquals("9090", resolved, "Should resolve to SERVER_PORT when PORT is absent");
    }

    @Test
    public void testPortPropertyResolutionDefault() {
        StandardEnvironment env = new StandardEnvironment();
        String template = "${PORT:${SERVER_PORT:8080}}";
        String resolved = env.resolvePlaceholders(template);
        assertEquals("8080", resolved, "Should resolve to default 8080 when no env var is present");
    }
}
