package com.example.piiprotector.pii;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PiiRequest(
        @NotBlank @Size(max = 1000) String name,
        @NotBlank @Email @Size(max = 1000) String email
) {
}
