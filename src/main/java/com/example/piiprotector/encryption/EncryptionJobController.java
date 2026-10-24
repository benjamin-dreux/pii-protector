package com.example.piiprotector.encryption;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/encryption-jobs")
public class EncryptionJobController {

    private final EncryptionJobService jobService;
    private final EncryptionJobMonitoringService monitoringService;

    public EncryptionJobController(
            EncryptionJobService jobService,
            EncryptionJobMonitoringService monitoringService
    ) {
        this.jobService = jobService;
        this.monitoringService = monitoringService;
    }

    @GetMapping
    public EncryptionJobMonitoringView getActiveJobs() {
        return monitoringService.getActiveJobs();
    }

    @PostMapping
    public ResponseEntity<EncryptionJobView> start(@Valid @RequestBody EncryptionJobRequest request) {
        EncryptionJobView job = jobService.start(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/admin/encryption-jobs/" + job.id()))
                .body(job);
    }

    @GetMapping("/{id}")
    public EncryptionJobView get(@PathVariable UUID id) {
        return jobService.get(id);
    }
}
