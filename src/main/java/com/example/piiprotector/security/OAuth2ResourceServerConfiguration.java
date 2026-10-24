package com.example.piiprotector.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OAuth2ResourceServerConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${pii.security.oauth2.required-scope:pii.jobs}") String requiredScope
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/admin/encryption-jobs", "/api/admin/encryption-jobs/**")
                        .hasAuthority("SCOPE_" + requiredScope)
                        .anyRequest().permitAll()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${pii.security.oauth2.issuer-uri:}") String issuerUri) {
        if (issuerUri.isBlank()) {
            return token -> {
                throw new JwtException("Configure pii.security.oauth2.issuer-uri before using admin endpoints");
            };
        }

        String normalizedIssuer = issuerUri.replaceAll("/+$", "");
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(normalizedIssuer + "/protocol/openid-connect/certs")
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(normalizedIssuer);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator));
        return decoder;
    }
}
