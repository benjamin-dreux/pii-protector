package com.example.piiprotector.encryption;

import com.example.piiprotector.crypto.PiiEncryptionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionJobServiceTest {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private JdbcDataSource defaultDataSource;
    private JdbcTemplate defaultJdbcTemplate;

    @BeforeEach
    void setUp() {
        defaultDataSource = dataSource("default_" + UUID.randomUUID().toString().replace("-", ""));
        defaultJdbcTemplate = new JdbcTemplate(defaultDataSource);
        createContactsTable(defaultJdbcTemplate, "Default Database Contact");
    }

    @AfterEach
    void closeDataSource() throws Exception {
        defaultDataSource.getConnection().close();
    }

    @Test
    void startsAndCompletesJobAgainstDefaultDatabase() {
        EncryptionJobService service = service(Runnable::run, new PiiEncryptionService(TEST_KEY));

        EncryptionJobView job = service.start(request(null));

        assertThat(job.status()).isEqualTo("SUCCEEDED");
        assertThat(job.database()).isEqualTo("default");
        assertThat(job.totalRows()).isEqualTo(1);
        assertThat(job.totalRowsByTable()).containsEntry("PUBLIC.CONTACTS", 1L);
        assertThat(job.processedRows()).isEqualTo(1);
        assertThat(job.processedRowsByTable()).containsEntry("PUBLIC.CONTACTS", 1L);
        assertThat(job.encryptedValues()).isEqualTo(1);
        assertThat(defaultJdbcTemplate.queryForObject(
                "SELECT display_name FROM contacts WHERE contact_id = 1",
                String.class
        )).isNotEqualTo("Default Database Contact");
    }

    @Test
    void directsJobToConnectionSuppliedInRequest() {
        JdbcDataSource secondaryDataSource = dataSource("secondary_" + UUID.randomUUID().toString().replace("-", ""));
        JdbcTemplate secondaryJdbcTemplate = new JdbcTemplate(secondaryDataSource);
        createContactsTable(secondaryJdbcTemplate, "Secondary Database Contact");
        EncryptionJobService service = service(Runnable::run, new PiiEncryptionService(TEST_KEY));

        EncryptionJobView job = service.start(request(new EncryptionDatabaseRequest(
                secondaryDataSource.getURL(),
                "sa",
                "",
                "org.h2.Driver"
        )));

        assertThat(job.status()).isEqualTo("SUCCEEDED");
        assertThat(job.database()).isEqualTo("h2");
        assertThat(secondaryJdbcTemplate.queryForObject(
                "SELECT display_name FROM contacts WHERE contact_id = 1",
                String.class
        )).isNotEqualTo("Secondary Database Contact");
        assertThat(defaultJdbcTemplate.queryForObject(
                "SELECT display_name FROM contacts WHERE contact_id = 1",
                String.class
        )).isEqualTo("Default Database Contact");
    }

    @Test
    void rejectsUnsupportedDriverAndUrlCombination() {
        EncryptionJobService service = service(Runnable::run, new PiiEncryptionService(TEST_KEY));
        EncryptionDatabaseRequest invalidConnection = new EncryptionDatabaseRequest(
                "jdbc:postgresql://localhost:5432/pii",
                "pii",
                "secret",
                "oracle.jdbc.OracleDriver"
        );

        assertThatThrownBy(() -> service.start(request(invalidConnection)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void reportsActiveAndQueuedJobsWithPerTableVolumes() {
        List<Runnable> pendingJobs = new java.util.ArrayList<>();
        EncryptionJobService service = service(pendingJobs::add, new PiiEncryptionService(TEST_KEY));
        EncryptionJobMonitoringService monitoringService = new EncryptionJobMonitoringService(service);

        service.start(request(null));
        service.start(request(null));

        EncryptionJobMonitoringView monitoring = monitoringService.getActiveJobs();

        assertThat(monitoring.runningJobs()).isZero();
        assertThat(monitoring.queuedJobs()).isEqualTo(2);
        assertThat(monitoring.jobs()).hasSize(2);
        assertThat(monitoring.jobs()).allSatisfy(job -> {
            assertThat(job.status()).isEqualTo("QUEUED");
            assertThat(job.database()).isEqualTo("default");
            assertThat(job.totalRows()).isEqualTo(1);
            assertThat(job.totalRowsByTable()).containsEntry("PUBLIC.CONTACTS", 1L);
            assertThat(job.processedRows()).isZero();
        });

        pendingJobs.forEach(Runnable::run);

        EncryptionJobMonitoringView completedMonitoring = monitoringService.getActiveJobs();
        assertThat(completedMonitoring.runningJobs()).isZero();
        assertThat(completedMonitoring.queuedJobs()).isZero();
        assertThat(completedMonitoring.jobs()).isEmpty();
    }

    @Test
    void runsJobsConcurrentlyWithIndependentDatabaseConnections() throws Exception {
        JdbcDataSource secondaryDataSource = dataSource("parallel_" + UUID.randomUUID().toString().replace("-", ""));
        JdbcTemplate secondaryJdbcTemplate = new JdbcTemplate(secondaryDataSource);
        createContactsTable(secondaryJdbcTemplate, "Secondary Parallel Contact");

        CountDownLatch bothJobsEncrypting = new CountDownLatch(2);
        CountDownLatch allowEncryptionToContinue = new CountDownLatch(1);
        PiiEncryptionService blockingEncryptionService = new PiiEncryptionService(TEST_KEY) {
            @Override
            public String encrypt(String plaintext, String purpose) {
                bothJobsEncrypting.countDown();
                try {
                    if (!allowEncryptionToContinue.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent jobs did not reach encryption together");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for concurrent jobs", exception);
                }
                return super.encrypt(plaintext, purpose);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            EncryptionJobService service = service(executor, blockingEncryptionService);
            EncryptionJobView defaultJob = service.start(request(null));
            EncryptionJobView secondaryJob = service.start(request(new EncryptionDatabaseRequest(
                    secondaryDataSource.getURL(),
                    "sa",
                    "",
                    "org.h2.Driver"
            )));

            assertThat(bothJobsEncrypting.await(5, TimeUnit.SECONDS)).isTrue();
            allowEncryptionToContinue.countDown();

            assertThat(awaitCompletion(service, defaultJob.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(awaitCompletion(service, secondaryJob.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(defaultJdbcTemplate.queryForObject(
                    "SELECT display_name FROM contacts WHERE contact_id = 1",
                    String.class
            )).isNotEqualTo("Default Database Contact");
            assertThat(secondaryJdbcTemplate.queryForObject(
                    "SELECT display_name FROM contacts WHERE contact_id = 1",
                    String.class
            )).isNotEqualTo("Secondary Parallel Contact");
        } finally {
            allowEncryptionToContinue.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private EncryptionJobService service(
            java.util.concurrent.Executor executor,
            PiiEncryptionService encryptionService
    ) {
        return new EncryptionJobService(defaultJdbcTemplate, defaultDataSource, encryptionService, executor);
    }

    private EncryptionJobRequest request(EncryptionDatabaseRequest connection) {
        return new EncryptionJobRequest(
                List.of(new EncryptionTableRequest(null, "contacts", List.of("display_name"), 1L)),
                1,
                connection
        );
    }

    private JdbcDataSource dataSource(String databaseName) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createContactsTable(JdbcTemplate jdbcTemplate, String name) {
        jdbcTemplate.execute("""
                CREATE TABLE contacts (
                    contact_id BIGINT PRIMARY KEY,
                    display_name VARCHAR(512)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO contacts (contact_id, display_name) VALUES (?, ?)",
                1L,
                name
        );
    }

    private EncryptionJobView awaitCompletion(EncryptionJobService service, UUID jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            EncryptionJobView job = service.get(jobId);
            if (job.status().equals("SUCCEEDED") || job.status().equals("FAILED")) {
                return job;
            }
            Thread.sleep(20);
        }
        return service.get(jobId);
    }
}
