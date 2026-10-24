package com.example.piiprotector.encryption;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EncryptionJobView(
        UUID id,
        String status,
        String database,
        int parallelism,
        List<String> tables,
        String currentTable,
        long totalRows,
        long processedRows,
        Map<String, Long> totalRowsByTable,
        Map<String, Long> processedRowsByTable,
        long encryptedValues,
        Map<String, Long> encryptedValuesByTable,
        Instant startedAt,
        Instant completedAt,
        String error
) {
}
