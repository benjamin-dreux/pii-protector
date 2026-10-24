package com.example.piiprotector.encryption;

import java.util.List;

public record EncryptionJobMonitoringView(
        int runningJobs,
        int queuedJobs,
        List<EncryptionJobView> jobs
) {
}
