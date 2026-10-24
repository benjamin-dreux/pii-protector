package com.example.piiprotector.pii;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/pii")
public class PiiController {

    private final PiiService piiService;

    public PiiController(PiiService piiService) {
        this.piiService = piiService;
    }

    @PostMapping
    public ResponseEntity<PiiResponse> create(@Valid @RequestBody PiiRequest request) {
        PiiResponse response = piiService.create(request);
        return ResponseEntity.created(URI.create("/api/pii/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public PiiResponse get(@PathVariable UUID id) {
        return piiService.get(id);
    }
}
