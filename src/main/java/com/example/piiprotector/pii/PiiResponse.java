package com.example.piiprotector.pii;

import java.util.UUID;

public record PiiResponse(UUID id, String name, String email) {
}
