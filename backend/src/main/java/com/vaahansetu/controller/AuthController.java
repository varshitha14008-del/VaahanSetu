package com.vaahansetu.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.AuthService.AuthResponse;
import com.vaahansetu.service.AuthService.TokenSession;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> credentials) {
        if (credentials == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Credentials are required."));
        }

        String username = credentials.get("username");
        String password = credentials.get("password");

        try {
            AuthResponse response = authService.login(username, password);
            return ResponseEntity.ok(Map.of(
                    "token", response.token(),
                    "userId", response.userId(),
                    "username", response.username(),
                    "fullName", response.fullName(),
                    "role", response.role(),
                    "message", response.message()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return authService.validateToken(authHeader)
                .map(session -> ResponseEntity.ok(Map.<String, Object>of(
                        "userId", session.userId(),
                        "username", session.username(),
                        "fullName", session.fullName(),
                        "role", session.role())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Not authenticated or token expired.")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.logout(authHeader);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
    }
}
