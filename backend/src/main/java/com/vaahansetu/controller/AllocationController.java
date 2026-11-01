package com.vaahansetu.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vaahansetu.dto.AllocationStatusResponse;
import com.vaahansetu.service.AllocationStatusService;
import com.vaahansetu.service.AuthService;
import com.vaahansetu.service.AuthService.TokenSession;

@RestController
@RequestMapping("/api/officer/allocations")
@CrossOrigin(origins = "*")
public class AllocationController {

    private final AllocationStatusService allocationStatusService;
    private final AuthService authService;

    public AllocationController(AllocationStatusService allocationStatusService, AuthService authService) {
        this.allocationStatusService = allocationStatusService;
        this.authService = authService;
    }

    @PostMapping("/run")
    public ResponseEntity<AllocationStatusResponse> runAllocation(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body) {

        authService.requireOfficer(authHeader);

        Long sessionId = null;
        if (body != null && body.get("sessionId") != null) {
            sessionId = Long.valueOf(body.get("sessionId").toString());
        }

        AllocationStatusResponse response = allocationStatusService.executeAllocation(sessionId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/latest")
    public ResponseEntity<AllocationStatusResponse> getLatestAllocation(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        authService.requireOfficer(authHeader);
        AllocationStatusResponse response = allocationStatusService.getLatestAllocation(null);
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<AllocationStatusResponse> getAllocationRun(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long runId) {

        authService.requireOfficer(authHeader);
        AllocationStatusResponse response = allocationStatusService.getAllocationRunDetails(runId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<Map<String, Object>> approveAllocation(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long runId) {

        TokenSession officer = authService.requireOfficer(authHeader);
        Map<String, Object> result = allocationStatusService.approveAndApplyProposal(runId, officer.userId());
        return ResponseEntity.ok(result);
    }
}
