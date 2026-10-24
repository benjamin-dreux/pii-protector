package com.example.piiprotector.encryption;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EncryptionDatabaseRequest(
        @NotBlank @Size(max = 2048) String url,
        @NotBlank @Size(max = 256) String username,
        @NotNull @Size(max = 1024) String password,
        @NotBlank @Size(max = 128) String driver
) {
}
