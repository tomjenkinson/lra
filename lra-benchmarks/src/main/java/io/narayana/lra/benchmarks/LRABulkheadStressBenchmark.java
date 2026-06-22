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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * JMH Benchmark for testing LRA Coordinator under high concurrency.
 *
 * This benchmark specifically tests the Bulkhead functionality by running
 * concurrent requests that exceed the default Bulkhead limit (typically 10).
 *
 * The Bulkhead annotation on the coordinator's startLRA endpoint controls
 * how many concurrent requests can be processed. This benchmark helps
 * verify the coordinator's behavior under stress.
 *
 * Thread counts can be configured to test different concurrency levels:
 * - 16 threads: Medium concurrency
 * - 32 threads: High concurrency
 * - 64 threads: Very high concurrency (as mentioned in the original issue)
 *
 * Related to:
 * - https://github.com/jbosstm/lra/issues/363
 * - https://redhat.atlassian.net/browse/JBTM-3984
 * - Zulip: Resource allocation for LRA Coordinator
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(1)
public class LRABulkheadStressBenchmark {

    private static final Logger log = Logger.getLogger(LRABulkheadStressBenchmark.class.getName());

    @Param({"http://localhost:8080/lra-coordinator"})
    private String coordinatorUrl;

    private Client client;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    @Setup(Level.Trial)
    public void setupClient() {
        log.info("Setting up stress test client for coordinator at: " + coordinatorUrl);
        client = ClientBuilder.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @TearDown(Level.Trial)
    public void tearDownClient() {
        if (client != null) {
            client.close();
        }
        log.info(String.format("Stress test completed - Success: %d, Failures: %d, Rejected (503): %d",
                successCount.get(), failureCount.get(), rejectedCount.get()));
    }

    /**
     * Medium concurrency test (16 concurrent threads).
     * This should stay within most Bulkhead configurations.
     */
    @Benchmark
    @Threads(16)
    public void mediumConcurrencyStartLRA(Blackhole blackhole) {
        executeStartLRA(blackhole);
    }

    /**
     * High concurrency test (32 concurrent threads).
     * This may exceed default Bulkhead limits (typically 10).
     */
    @Benchmark
    @Threads(32)
    public void highConcurrencyStartLRA(Blackhole blackhole) {
        executeStartLRA(blackhole);
    }

    /**
     * Very high concurrency test (64 concurrent threads).
     * This is the scenario mentioned in the original issue where
     * 64 concurrent requests were used to test the Bulkhead.
     */
    @Benchmark
    @Threads(64)
    public void veryHighConcurrencyStartLRA(Blackhole blackhole) {
        executeStartLRA(blackhole);
    }

    /**
     * Complete lifecycle under high concurrency (32 threads).
     */
    @Benchmark
    @Threads(32)
    public void concurrentLifecycle(Blackhole blackhole) {
        URI lraId = null;
        try {
            // Start LRA
            try (Response startResponse = client.target(coordinatorUrl)
                    .path("start")
                    .queryParam("ClientID", "stress-benchmark-" + Thread.currentThread().getId())
                    .request(MediaType.TEXT_PLAIN)
                    .post(Entity.text(""))) {

                int status = startResponse.getStatus();
                if (status == 201) {
                    lraId = URI.create(startResponse.readEntity(String.class));
                    blackhole.consume(lraId);
                } else if (status == 503) {
                    rejectedCount.incrementAndGet();
                    return;
                } else {
                    failureCount.incrementAndGet();
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

                    if (closeResponse.getStatus() == 200 || closeResponse.getStatus() == 202) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                    blackhole.consume(closeResponse.getStatus());
                }
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warning("Error in concurrent lifecycle: " + e.getMessage());
        }
    }

    /**
     * Rapid fire test: Many quick LRA creations and completions.
     * Tests the coordinator's ability to handle rapid state transitions.
     */
    @Benchmark
    @Threads(32)
    public void rapidFireLRACycles(Blackhole blackhole) {
        for (int i = 0; i < 5; i++) {
            URI lraId = null;
            try {
                // Start
                try (Response startResponse = client.target(coordinatorUrl)
                        .path("start")
                        .queryParam("ClientID", "rapid-fire-" + Thread.currentThread().getId() + "-" + i)
                        .request(MediaType.TEXT_PLAIN)
                        .post(Entity.text(""))) {

                    if (startResponse.getStatus() == 201) {
                        lraId = URI.create(startResponse.readEntity(String.class));
                    } else if (startResponse.getStatus() == 503) {
                        rejectedCount.incrementAndGet();
                        continue;
                    } else {
                        failureCount.incrementAndGet();
                        continue;
                    }
                }

                // Close
                if (lraId != null) {
                    String lraIdStr = extractLRAId(lraId);
                    try (Response closeResponse = client.target(coordinatorUrl)
                            .path(lraIdStr)
                            .path("close")
                            .request(MediaType.TEXT_PLAIN)
                            .put(Entity.text(""))) {

                        if (closeResponse.getStatus() == 200 || closeResponse.getStatus() == 202) {
                            successCount.incrementAndGet();
                        }
                        blackhole.consume(closeResponse.getStatus());
                    }
                }
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        }
    }

    /**
     * Mixed operations under concurrency.
     * Combines starts, status checks, and closes.
     */
    @Benchmark
    @Threads(24)
    public void mixedConcurrentOperations(Blackhole blackhole, MixedOpState state) {
        // 50% chance: start new LRA
        // 30% chance: check status of existing LRA
        // 20% chance: close existing LRA

        int operation = (int) (Math.random() * 10);

        if (operation < 5) {
            // Start new LRA
            executeStartLRA(blackhole);
        } else if (operation < 8 && state.lraId != null) {
            // Check status
            try {
                String lraIdStr = extractLRAId(state.lraId);
                try (Response response = client.target(coordinatorUrl)
                        .path(lraIdStr)
                        .path("status")
                        .request(MediaType.TEXT_PLAIN)
                        .get()) {

                    blackhole.consume(response.readEntity(String.class));
                }
            } catch (Exception e) {
                log.fine("Error checking status: " + e.getMessage());
            }
        } else if (state.lraId != null) {
            // Close LRA
            try {
                String lraIdStr = extractLRAId(state.lraId);
                try (Response response = client.target(coordinatorUrl)
                        .path(lraIdStr)
                        .path("close")
                        .request(MediaType.TEXT_PLAIN)
                        .put(Entity.text(""))) {

                    blackhole.consume(response.getStatus());
                }
                state.lraId = null; // Reset after close
            } catch (Exception e) {
                log.fine("Error closing LRA: " + e.getMessage());
            }
        }
    }

    /**
     * Core LRA start operation.
     */
    private void executeStartLRA(Blackhole blackhole) {
        try (Response response = client.target(coordinatorUrl)
                .path("start")
                .queryParam("ClientID", "stress-client-" + Thread.currentThread().getId())
                .request(MediaType.TEXT_PLAIN)
                .post(Entity.text(""))) {

            int status = response.getStatus();
            if (status == 201) {
                String lraId = response.readEntity(String.class);
                successCount.incrementAndGet();
                blackhole.consume(lraId);

                // Clean up: cancel the LRA to avoid accumulation
                try {
                    URI lraUri = URI.create(lraId);
                    String lraIdStr = extractLRAId(lraUri);
                    client.target(coordinatorUrl)
                            .path(lraIdStr)
                            .path("cancel")
                            .request(MediaType.TEXT_PLAIN)
                            .put(Entity.text(""))
                            .close();
                } catch (Exception e) {
                    // Cleanup failure is not critical for benchmark
                }
            } else if (status == 503) {
                // Bulkhead rejection (service unavailable)
                rejectedCount.incrementAndGet();
                blackhole.consume(status);
            } else {
                failureCount.incrementAndGet();
                log.warning("Unexpected response: " + status);
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.severe("Error in executeStartLRA: " + e.getMessage());
        }
    }

    private String extractLRAId(URI lraUri) {
        String path = lraUri.getPath();
        int lastSlash = path.lastIndexOf('/');
        return path.substring(lastSlash + 1);
    }

    /**
     * State for mixed operations benchmark.
     */
    @State(Scope.Thread)
    public static class MixedOpState {
        private URI lraId;
        private Client stateClient;

        @Setup(Level.Iteration)
        public void setup(LRABulkheadStressBenchmark benchmark) {
            stateClient = ClientBuilder.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            // Pre-create an LRA for this thread
            try (Response response = stateClient.target(benchmark.coordinatorUrl)
                    .path("start")
                    .queryParam("ClientID", "mixed-op-state-" + Thread.currentThread().getId())
                    .request(MediaType.TEXT_PLAIN)
                    .post(Entity.text(""))) {

                if (response.getStatus() == 201) {
                    lraId = URI.create(response.readEntity(String.class));
                }
            } catch (Exception e) {
                log.warning("Failed to create LRA for mixed op state: " + e.getMessage());
            }
        }

        @TearDown(Level.Iteration)
        public void tearDown(LRABulkheadStressBenchmark benchmark) {
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
                    log.fine("Cleanup of mixed op LRA failed: " + e.getMessage());
                }
            }
            if (stateClient != null) {
                stateClient.close();
            }
        }
    }
}
