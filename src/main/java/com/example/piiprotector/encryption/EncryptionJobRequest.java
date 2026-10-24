package com.example.piiprotector.encryption;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EncryptionJobRequest(
        @NotEmpty @Size(max = 50) List<@Valid EncryptionTableRequest> tables,
        @Min(1) @Max(10000) Integer batchSize,
        @Valid EncryptionDatabaseRequest connection
) {
}
