/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.benchmarks;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * JMH Benchmark for LRA Coordinator operations.
 *
 * This benchmark tests the performance and stress characteristics of the LRA coordinator,
 * including the Bulkhead fault tolerance mechanism.
 *
 * Related to:
 * - https://github.com/jbosstm/lra/issues/363
 * - https://redhat.atlassian.net/browse/JBTM-3984
 *
 * Usage:
 * 1. Start the LRA coordinator (e.g., using WildFly or Quarkus)
 * 2. Run: java -jar target/lra-benchmarks.jar
 *
 * Configuration:
 * - Coordinator URL: Set via -DcoordinatorUrl (default: http://localhost:8080/lra-coordinator)
 * - Bulkhead config: Via microprofile-config.properties or system properties
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(1)
public class LRACoordinatorBenchmark {

    private static final Logger log = Logger.getLogger(LRACoordinatorBenchmark.class.getName());

    @Param({"http://localhost:8080/lra-coordinator"})
    private String coordinatorUrl;

    private Client client;

    @Setup(Level.Trial)
    public void setupClient() {
        log.info("Setting up JAX-RS client for coordinator at: " + coordinatorUrl);
        client = ClientBuilder.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDownClient() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Benchmark: Start a new LRA.
     * This tests the Bulkhead-protected startLRA endpoint.
     */
    @Benchmark
    @Group("lra_lifecycle")
    @GroupThreads(1)
    public void startLRA(Blackhole blackhole) {
        try (Response response = client.target(coordinatorUrl)
                .path("start")
                .queryParam("ClientID", "benchmark-client")
                .request(MediaType.TEXT_PLAIN)
                .post(Entity.text(""))) {

            if (response.getStatus() == 201) {
                String lraId = response.readEntity(String.class);
                blackhole.consume(lraId);
            } else {
                log.warning("Failed to start LRA: " + response.getStatus());
            }
        } catch (Exception e) {
            log.severe("Error starting LRA: " + e.getMessage());
        }
    }

    /**
     * Benchmark: Complete LRA lifecycle (start + close).
     * This provides end-to-end performance measurement.
     */
    @Benchmark
    public void completeLRALifecycle(Blackhole blackhole) {
        URI lraId = null;
        try {
            // Start LRA
            try (Response startResponse = client.target(coordinatorUrl)
                    .path("start")
                    .queryParam("ClientID", "benchmark-client")
                    .request(MediaType.TEXT_PLAIN)
                    .post(Entity.text(""))) {

                if (startResponse.getStatus() == 201) {
                    lraId = URI.create(startResponse.readEntity(String.class));
                    blackhole.consume(lraId);
                } else {
                    log.warning("Failed to start LRA: " + startResponse.getStatus());
                    return;
                }
            }

            // Close LRA
            if (lraId != null) {
                String lraIdStr = extractLRAId(lraId);
                try (Response closeResponse = client.target(coordinatorUrl)
                        .path(lraIdStr)
                        .path("close")
                        .request(MediaType.TEXT_PLAIN)
                        .put(Entity.text(""))) {

                    blackhole.consume(closeResponse.getStatus());
                }
            }
        } catch (Exception e) {
            log.severe("Error in LRA lifecycle: " + e.getMessage());
        }
    }

    /**
     * Benchmark: Cancel LRA lifecycle (start + cancel).
     */
    @Benchmark
    public void cancelLRALifecycle(Blackhole blackhole) {
        URI lraId = null;
        try {
            // Start LRA
            try (Response startResponse = client.target(coordinatorUrl)
                    .path("start")
                    .queryParam("ClientID", "benchmark-client")
                    .request(MediaType.TEXT_PLAIN)
                    .post(Entity.text(""))) {

                if (startResponse.getStatus() == 201) {
                    lraId = URI.create(startResponse.readEntity(String.class));
                    blackhole.consume(lraId);
                } else {
                    return;
                }
            }

            // Cancel LRA
            if (lraId != null) {
                String lraIdStr = extractLRAId(lraId);
                try (Response cancelResponse = client.target(coordinatorUrl)
                        .path(lraIdStr)
                        .path("cancel")
                        .request(MediaType.TEXT_PLAIN)
                        .put(Entity.text(""))) {

                    blackhole.consume(cancelResponse.getStatus());
                }
            }
        } catch (Exception e) {
            log.severe("Error in LRA cancel lifecycle: " + e.getMessage());
        }
    }

    /**
     * Benchmark: Get LRA status.
     */
    @Benchmark
    public void getLRAStatus(LRAState state, Blackhole blackhole) {
        if (state.lraId != null) {
            try {
                String lraIdStr = extractLRAId(state.lraId);
                try (Response response = client.target(coordinatorUrl)
                        .path(lraIdStr)
                        .path("status")
                        .request(MediaType.TEXT_PLAIN)
                        .get()) {

                    String status = response.readEntity(String.class);
                    blackhole.consume(status);
                }
            } catch (Exception e) {
                log.severe("Error getting LRA status: " + e.getMessage());
            }
        }
    }

    /**
     * Benchmark: List all LRAs.
     */
    @Benchmark
    public void getAllLRAs(Blackhole blackhole) {
        try (Response response = client.target(coordinatorUrl)
                .request(MediaType.APPLICATION_JSON)
                .get()) {

            String lraList = response.readEntity(String.class);
            blackhole.consume(lraList);
        } catch (Exception e) {
            log.severe("Error getting all LRAs: " + e.getMessage());
        }
    }

    /**
     * Extract the LRA ID from the full URI.
     */
    private String extractLRAId(URI lraUri) {
        String path = lraUri.getPath();
        int lastSlash = path.lastIndexOf('/');
        return path.substring(lastSlash + 1);
    }

    /**
     * State holder for LRA operations that need a pre-created LRA.
     */
    @State(Scope.Thread)
    public static class LRAState {
        private URI lraId;
        private Client stateClient;

        @Setup(Level.Iteration)
        public void setupLRA(LRACoordinatorBenchmark benchmark) {
            stateClient = ClientBuilder.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            try (Response response = stateClient.target(benchmark.coordinatorUrl)
                    .path("start")
                    .queryParam("ClientID", "benchmark-state-client")
                    .request(MediaType.TEXT_PLAIN)
                    .post(Entity.text(""))) {

                if (response.getStatus() == 201) {
                    lraId = URI.create(response.readEntity(String.class));
                    log.info("Created LRA for state: " + lraId);
                }
            } catch (Exception e) {
                log.severe("Failed to create LRA for state: " + e.getMessage());
            }
        }

        @TearDown(Level.Iteration)
        public void tearDownLRA(LRACoordinatorBenchmark benchmark) {
            if (lraId != null && stateClient != null) {
                try {
                    String lraIdStr = benchmark.extractLRAId(lraId);
                    stateClient.target(benchmark.coordinatorUrl)
                            .path(lraIdStr)
                            .path("cancel")
                            .request(MediaType.TEXT_PLAIN)
                            .put(Entity.text(""))
                            .close();
                } catch (Exception e) {
                    log.warning("Failed to clean up LRA: " + e.getMessage());
                }
            }
            if (stateClient != null) {
                stateClient.close();
            }
        }
    }
}
