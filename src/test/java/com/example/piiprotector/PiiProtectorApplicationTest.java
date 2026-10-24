package com.example.piiprotector;

import com.example.piiprotector.pii.ProtectedPiiRepository;
import com.example.piiprotector.crypto.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.sql.Connection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:pii;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "pii.encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "pii.security.oauth2.issuer-uri=http://localhost:8180/realms/pii-protector"
        }
)
class PiiProtectorApplicationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOAuth2Configuration {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                String scope = switch (token) {
                    case "test-admin-token" -> "pii.jobs";
                    case "test-reader-token" -> "pii.read";
                    default -> throw new JwtException("Invalid test token");
                };
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .claim("iss", "http://localhost:8180/realms/pii-protector")
                        .claim("sub", "test-user")
                        .claim("scope", scope)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(60))
                        .build();
            };
        }
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ProtectedPiiRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PiiEncryptionService encryptionService;

    @BeforeEach
    void prepareAddressBookTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contacts (
                    contact_id BIGINT PRIMARY KEY,
                    display_name VARCHAR(512),
                    date_of_birth VARCHAR(128)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS customer_directory (
                    customer_id BIGINT PRIMARY KEY,
                    full_name VARCHAR(512)
                )
                """);
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM customer_directory");
        jdbcTemplate.update(
                "INSERT INTO contacts (contact_id, display_name, date_of_birth) VALUES (?, ?, ?)",
                1L,
                "Synthetic Contact",
                "1985-04-12"
        );
        jdbcTemplate.update(
                "INSERT INTO customer_directory (customer_id, full_name) VALUES (?, ?)",
                22L,
                "Dynamic Table Contact"
        );
    }

    @Test
    void createsAndReadsPiiWhileKeepingOnlyCiphertextInDatabase() throws Exception {
        String requestBody = """
                {"name":"Alice Example","email":"alice@example.org"}
                """;
        HttpRequest createRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/pii"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> createResponse = HTTP_CLIENT.send(
                createRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(createResponse.statusCode()).isEqualTo(201);
        URI recordUri = URI.create("http://localhost:" + port)
                .resolve(createResponse.headers().firstValue("Location").orElseThrow());
        UUID id = UUID.fromString(recordUri.getPath().substring(recordUri.getPath().lastIndexOf('/') + 1));

        var storedRecord = repository.findById(id).orElseThrow();
        assertThat(storedRecord.getEncryptedName()).doesNotContain("Alice Example");
        assertThat(storedRecord.getEncryptedEmail()).doesNotContain("alice@example.org");

        HttpRequest getRequest = HttpRequest.newBuilder(recordUri).GET().build();
        HttpResponse<String> getResponse = HTTP_CLIENT.send(
                getRequest,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.body()).contains("\"name\":\"Alice Example\"");
        assertThat(getResponse.body()).contains("\"email\":\"alice@example.org\"");
    }

    @Test
    void encryptsExistingPlaintextRowsThroughAuthenticatedAsyncApi() throws Exception {
        HttpRequest startRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer test-admin-token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "tables":[
                            {"name":"contacts","columns":["display_name","date_of_birth"]},
                            {"schema":"PUBLIC","name":"customer_directory","columns":["full_name"]}
                          ],
                          "batchSize":1
                        }
                        """))
                .build();

        HttpResponse<String> startResponse = HTTP_CLIENT.send(
                startRequest,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(startResponse.statusCode()).isEqualTo(202);
        URI statusUri = URI.create("http://localhost:" + port)
                .resolve(startResponse.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> statusResponse = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpRequest statusRequest = HttpRequest.newBuilder(statusUri)
                    .header("Authorization", "Bearer test-admin-token")
                    .GET()
                    .build();
            statusResponse = HTTP_CLIENT.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            if (statusResponse.body().contains("\"status\":\"SUCCEEDED\"")
                    || statusResponse.body().contains("\"status\":\"FAILED\"")) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(statusResponse).isNotNull();
        assertThat(statusResponse.body()).contains("\"status\":\"SUCCEEDED\"");
        assertThat(statusResponse.body()).contains("\"encryptedValues\":3");

        var storedContact = jdbcTemplate.queryForMap(
                "SELECT display_name, date_of_birth FROM contacts WHERE contact_id = 1"
        );
        String encryptedName = (String) storedContact.get("display_name");
        String encryptedDateOfBirth = (String) storedContact.get("date_of_birth");
        assertThat(encryptedName).hasSize("Synthetic Contact".length())
                .doesNotContain("Synthetic Contact");
        assertThat(encryptionService.decrypt(encryptedName, "public.contacts.display_name"))
                .isEqualTo("Synthetic Contact");
        assertThat(encryptionService.decrypt(encryptedDateOfBirth, "public.contacts.date_of_birth"))
                .isEqualTo("1985-04-12");

        String encryptedDynamicName = jdbcTemplate.queryForObject(
                "SELECT full_name FROM customer_directory WHERE customer_id = 22",
                String.class
        );
        assertThat(encryptionService.decrypt(encryptedDynamicName, "public.customer_directory.full_name"))
                .isEqualTo("Dynamic Table Contact");

        HttpResponse<String> secondStartResponse = HTTP_CLIENT.send(
                startRequest,
                HttpResponse.BodyHandlers.ofString()
        );
        URI secondStatusUri = URI.create("http://localhost:" + port)
                .resolve(secondStartResponse.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> secondStatusResponse = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpRequest statusRequest = HttpRequest.newBuilder(secondStatusUri)
                    .header("Authorization", "Bearer test-admin-token")
                    .GET()
                    .build();
            secondStatusResponse = HTTP_CLIENT.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            if (secondStatusResponse.body().contains("\"status\":\"SUCCEEDED\"")
                    || secondStatusResponse.body().contains("\"status\":\"FAILED\"")) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(secondStartResponse.statusCode()).isEqualTo(202);
        assertThat(secondStatusResponse).isNotNull();
        assertThat(secondStatusResponse.body()).contains("\"status\":\"SUCCEEDED\"");
        assertThat(secondStatusResponse.body()).contains("\"encryptedValues\":3");
    }

    @Test
    void appliesMaxRowsIndependentlyToEachTable() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO contacts (contact_id, display_name, date_of_birth) VALUES (?, ?, ?)",
                2L,
                "Second Synthetic Contact",
                "1986-05-13"
        );
        jdbcTemplate.update(
                "INSERT INTO customer_directory (customer_id, full_name) VALUES (?, ?)",
                23L,
                "Second Dynamic Table Contact"
        );

        HttpRequest startRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer test-admin-token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "tables":[
                            {"name":"contacts","columns":["display_name","date_of_birth"],"maxRows":1},
                            {"name":"customer_directory","columns":["full_name"],"maxRows":1}
                          ]
                        }
                        """))
                .build();
        HttpResponse<String> startResponse = HTTP_CLIENT.send(
                startRequest,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(startResponse.statusCode()).isEqualTo(202);
        URI statusUri = URI.create("http://localhost:" + port)
                .resolve(startResponse.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> statusResponse = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpRequest statusRequest = HttpRequest.newBuilder(statusUri)
                    .header("Authorization", "Bearer test-admin-token")
                    .GET()
                    .build();
            statusResponse = HTTP_CLIENT.send(statusRequest, HttpResponse.BodyHandlers.ofString());
            if (statusResponse.body().contains("\"status\":\"SUCCEEDED\"")
                    || statusResponse.body().contains("\"status\":\"FAILED\"")) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(statusResponse).isNotNull();
        assertThat(statusResponse.body())
                .contains("\"status\":\"SUCCEEDED\"")
                .contains("\"processedRows\":2")
                .contains("\"encryptedValues\":3");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM contacts WHERE contact_id = 1",
                String.class
        )).isNotEqualTo("Synthetic Contact");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT display_name FROM contacts WHERE contact_id = 2",
                String.class
        )).isEqualTo("Second Synthetic Contact");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT full_name FROM customer_directory WHERE customer_id = 22",
                String.class
        )).isNotEqualTo("Dynamic Table Contact");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT full_name FROM customer_directory WHERE customer_id = 23",
                String.class
        )).isEqualTo("Second Dynamic Table Contact");
    }

    @Test
    void runsJobsAgainstDifferentDynamicDatabasesConcurrently() throws Exception {
        JdbcTemplate secondaryJdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource("jdbc:h2:mem:secondary;DB_CLOSE_DELAY=-1", "sa", "")
        );
        secondaryJdbcTemplate.execute("""
                CREATE TABLE contacts (
                    contact_id BIGINT PRIMARY KEY,
                    display_name VARCHAR(512)
                )
                """);
        secondaryJdbcTemplate.update(
                "INSERT INTO contacts (contact_id, display_name) VALUES (?, ?)",
                1L,
                "Dynamic Secondary Database Contact"
        );

        try (Connection lockConnection = jdbcTemplate.getDataSource().getConnection()) {
            lockConnection.setAutoCommit(false);
            lockConnection.createStatement().execute("SET LOCK_TIMEOUT 30000");
            lockConnection.createStatement().execute(
                    "UPDATE contacts SET display_name = 'Synthetic Contact' WHERE contact_id = 1"
            );

            HttpResponse<String> primaryStart = startJob("""
                    {"tables":[{"name":"contacts","columns":["display_name"],"maxRows":1}]}
                    """);
            assertThat(primaryStart.statusCode()).isEqualTo(202);
            URI primaryStatusUri = jobStatusUri(primaryStart);
            HttpResponse<String> primaryStatus = awaitStatus(primaryStatusUri, "RUNNING");

            HttpResponse<String> secondaryStart = startJob("""
                    {
                      "connection":{
                        "url":"jdbc:h2:mem:secondary;DB_CLOSE_DELAY=-1",
                        "username":"sa",
                        "password":"",
                        "driver":"org.h2.Driver"
                      },
                      "tables":[{"name":"contacts","columns":["display_name"],"maxRows":1}]
                    }
                    """);
            assertThat(secondaryStart.statusCode()).isEqualTo(202);
            URI secondaryStatusUri = jobStatusUri(secondaryStart);
            HttpResponse<String> secondaryStatus = awaitStatus(secondaryStatusUri, "SUCCEEDED");

            assertThat(primaryStatus.body()).contains("\"database\":\"default\"");
            assertThat(secondaryStatus.body())
                    .contains("\"database\":\"h2\"")
                    .contains("\"status\":\"SUCCEEDED\"");
            assertThat(awaitStatus(primaryStatusUri, "RUNNING").body()).contains("\"status\":\"RUNNING\"");
            HttpResponse<String> monitorResponse = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                            .header("Authorization", "Bearer test-admin-token")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(monitorResponse.statusCode()).isEqualTo(200);
            assertThat(monitorResponse.body())
                    .contains("\"runningJobs\":1")
                    .contains("\"queuedJobs\":0")
                    .contains("\"database\":\"default\"")
                    .contains("\"totalRows\":1")
                    .contains("\"processedRows\":0");
            assertThat(secondaryJdbcTemplate.queryForObject(
                    "SELECT display_name FROM contacts WHERE contact_id = 1",
                    String.class
            )).isNotEqualTo("Dynamic Secondary Database Contact");

            lockConnection.rollback();
            HttpResponse<String> completedPrimary = awaitStatus(primaryStatusUri, "SUCCEEDED");
            assertThat(completedPrimary.body()).contains("\"status\":\"SUCCEEDED\"");
        }
    }

    private HttpResponse<String> startJob(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer test-admin-token")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI jobStatusUri(HttpResponse<String> response) {
        return URI.create("http://localhost:" + port)
                .resolve(response.headers().firstValue("Location").orElseThrow());
    }

    private HttpResponse<String> awaitStatus(URI statusUri, String expectedStatus) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 500; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(statusUri)
                    .header("Authorization", "Bearer test-admin-token")
                    .GET()
                    .build();
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("\"status\":\"" + expectedStatus + "\"")) {
                return response;
            }
            if (response.body().contains("\"status\":\"FAILED\"")) {
                break;
            }
            Thread.sleep(20);
        }
        return response;
    }

    @Test
    void requiresBearerTokenWithAdministrativeScope() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"tables":[{"name":"contacts","columns":["display_name"]}]}
                        """))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);

        HttpRequest monitorRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .GET()
                .build();
        HttpResponse<String> monitorResponse = HTTP_CLIENT.send(
                monitorRequest,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(monitorResponse.statusCode()).isEqualTo(401);

        HttpRequest insufficientScopeRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .header("Authorization", "Bearer test-reader-token")
                .GET()
                .build();
        HttpResponse<String> insufficientScopeResponse = HTTP_CLIENT.send(
                insufficientScopeRequest,
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(insufficientScopeResponse.statusCode()).isEqualTo(403);
    }

    @Test
    void rejectsUnsafeDynamicSqlIdentifiers() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/admin/encryption-jobs"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer test-admin-token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"tables":[{"name":"contacts; DROP TABLE contacts","columns":["display_name"]}]}
                        """))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }
}
