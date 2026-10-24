package com.example.piiprotector.encryption;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EncryptionTableRequest(
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_$#]*") String schema,
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_$#]*") String name,
        @NotEmpty @Size(max = 50)
        List<@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_$#]*") String> columns,
        @Min(1) Long maxRows
) {
}
