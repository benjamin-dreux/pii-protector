package com.example.piiprotector.pii;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "protected_pii")
public class ProtectedPii {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encrypted_name", nullable = false, length = 2048)
    private String encryptedName;

    @Column(name = "encrypted_email", nullable = false, length = 2048)
    private String encryptedEmail;

    protected ProtectedPii() {
    }

    public ProtectedPii(String encryptedName, String encryptedEmail) {
        this.encryptedName = encryptedName;
        this.encryptedEmail = encryptedEmail;
    }

    public UUID getId() {
        return id;
    }

    public String getEncryptedName() {
        return encryptedName;
    }

    public String getEncryptedEmail() {
        return encryptedEmail;
    }
}
