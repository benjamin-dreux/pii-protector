package com.example.piiprotector.encryption;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EncryptionJobMonitoringService {

    private final EncryptionJobService jobService;

    public EncryptionJobMonitoringService(EncryptionJobService jobService) {
        this.jobService = jobService;
    }

    public EncryptionJobMonitoringView getActiveJobs() {
        List<EncryptionJobView> jobs = jobService.getActiveJobs();
        int runningJobs = (int) jobs.stream().filter(job -> job.status().equals("RUNNING")).count();
        int queuedJobs = (int) jobs.stream().filter(job -> job.status().equals("QUEUED")).count();
        return new EncryptionJobMonitoringView(runningJobs, queuedJobs, jobs);
    }
}
