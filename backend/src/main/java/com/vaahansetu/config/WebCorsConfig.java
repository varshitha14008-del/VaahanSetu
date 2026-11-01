package com.vaahansetu.config;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(WebCorsConfig.class);

    @Value("${app.cors.allowed-origins:https://vaahansetu.onrender.com,http://localhost:8080,http://localhost:3000,http://127.0.0.1:8080,http://127.0.0.1:5500}")
    private String allowedOriginsConfig;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        logger.info("Configured CORS allowlist for /api/** endpoints: {}", Arrays.toString(origins));

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "X-Requested-With")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
