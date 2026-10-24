package com.example.piiprotector.pii;

import com.example.piiprotector.crypto.PiiEncryptionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class PiiService {

    private static final String NAME_PURPOSE = "pii.name";
    private static final String EMAIL_PURPOSE = "pii.email";

    private final ProtectedPiiRepository repository;
    private final PiiEncryptionService encryptionService;

    public PiiService(ProtectedPiiRepository repository, PiiEncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public PiiResponse create(PiiRequest request) {
        ProtectedPii entity = new ProtectedPii(
                encryptionService.encrypt(request.name(), NAME_PURPOSE),
                encryptionService.encrypt(request.email(), EMAIL_PURPOSE)
        );
        ProtectedPii saved = repository.save(entity);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PiiResponse get(UUID id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PII record not found"));
    }

    private PiiResponse toResponse(ProtectedPii entity) {
        return new PiiResponse(
                entity.getId(),
                encryptionService.decrypt(entity.getEncryptedName(), NAME_PURPOSE),
                encryptionService.decrypt(entity.getEncryptedEmail(), EMAIL_PURPOSE)
        );
    }
}
