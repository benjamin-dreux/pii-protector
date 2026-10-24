package com.example.piiprotector.pii;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProtectedPiiRepository extends JpaRepository<ProtectedPii, UUID> {
}
