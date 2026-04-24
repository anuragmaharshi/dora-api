package com.dora.controllers;

import com.dora.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    // BuildProperties is produced by the spring-boot-maven-plugin build-info goal.
    // It is absent in @WebMvcTest slice contexts and in IDE runs without a prior package phase.
    // Field injection with required=false lets Spring inject null cleanly in both cases.
    @Autowired(required = false)
    private BuildProperties buildProperties;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String version = buildProperties != null ? buildProperties.getVersion() : "dev";
        return ResponseEntity.ok(new HealthResponse("healthy", version, Instant.now()));
    }
}
