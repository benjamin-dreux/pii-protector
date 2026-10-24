package com.example.piiprotector.performance;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class EncryptionApiSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final String ACCESS_TOKEN = System.getenv().getOrDefault(
            "OAUTH2_ACCESS_TOKEN",
            System.getProperty("accessToken", "")
    );
    private static final String TABLES_JSON = System.getenv().getOrDefault(
            "PII_ENCRYPTION_TABLES_JSON",
            System.getProperty(
                    "tablesJson",
                    """
                            {"tables":[{"schema":"public","name":"contacts","columns":["display_name","date_of_birth"]}],"batchSize":1000}
                            """
            )
    ).trim();
    private static final int MAX_POLLS = Integer.getInteger("maxPolls", 14400);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final HttpRequestActionBuilder pollJob = http("Poll encryption job")
            .get(session -> session.getString("jobLocation"))
            .header("Authorization", "Bearer " + ACCESS_TOKEN)
            .check(
                    status().is(200),
                    jsonPath("$.status").saveAs("jobStatus")
            );

    private final ScenarioBuilder encryptionWorkflow = scenario("Encrypt existing PII through API")
            .exec(group("Encryption job end-to-end").on(
                    exec(http("Start encryption job")
                            .post("/api/admin/encryption-jobs")
                            .header("Authorization", "Bearer " + ACCESS_TOKEN)
                            .body(StringBody(TABLES_JSON))
                            .check(
                                    status().is(202),
                                    header("Location").saveAs("jobLocation")
                            ))
                            .exec(exitHereIfFailed())
                            .exec(session -> session.set("pollCount", 1))
                            .pause(1)
                            .exec(pollJob)
                            .asLongAs(
                                    session -> {
                                        String status = session.getString("jobStatus");
                                        return !"SUCCEEDED".equals(status)
                                                && !"FAILED".equals(status)
                                                && session.getInt("pollCount") < MAX_POLLS;
                                    }
                            ).on(pause(1)
                                    .exec(pollJob)
                                    .exec(session -> session.set(
                                            "pollCount",
                                            session.getInt("pollCount") + 1
                                    )))
                            .exec(session -> {
                                if (!"SUCCEEDED".equals(session.getString("jobStatus"))) {
                                    return session.markAsFailed();
                                }
                                return session;
                            })
            ));

    {
        if (ACCESS_TOKEN.isBlank()) {
            throw new IllegalArgumentException(
                    "Set OAUTH2_ACCESS_TOKEN or pass -DaccessToken=<token>"
            );
        }
        if (MAX_POLLS < 1) {
            throw new IllegalArgumentException("-DmaxPolls must be greater than zero");
        }

        setUp(encryptionWorkflow.injectOpen(atOnceUsers(1)))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().count().is(0L));
    }
}
