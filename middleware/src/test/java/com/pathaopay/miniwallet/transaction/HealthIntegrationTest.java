package com.pathaopay.miniwallet.transaction;

import com.pathaopay.miniwallet.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Actuator health: the probes the container healthcheck uses, and nothing else exposed. */
class HealthIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Test
    void livenessAndReadinessReportUpWithoutDetails() {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            RestClient.ResponseSpec response = RestClient.create("http://localhost:" + port).get().uri(path).retrieve();
            JsonNode body = JSON.readTree(response.body(String.class));

            assertEquals("UP", body.get("status").asString(), path);
            assertFalse(body.has("components"), path + " must not leak component details");
        }
    }

    @Test
    void onlyHealthIsExposed() {
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/info"}) {
            int status = RestClient.create("http://localhost:" + port).get().uri(path)
                    .exchange((request, response) -> response.getStatusCode().value());

            assertEquals(404, status, path);
        }
    }
}
