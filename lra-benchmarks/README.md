# LRA Coordinator JMH Benchmarks

This module contains JMH (Java Microbenchmark Harness) benchmarks for stress testing the LRA (Long Running Actions) Coordinator, with a focus on the Bulkhead fault tolerance mechanism.

## Background

These benchmarks were created in response to:
- GitHub Issue: [jbosstm/lra#363](https://github.com/jbosstm/lra/issues/363)
- Jira Issue: [JBTM-3984](https://redhat.atlassian.net/browse/JBTM-3984)
- Zulip Discussion: Resource allocation for LRA Coordinator

The Bulkhead annotation on the coordinator's `startLRA` endpoint limits concurrent request processing. These benchmarks help verify the coordinator's behavior under various stress levels.

## Benchmarks Included

### 1. LRACoordinatorBenchmark
Standard performance benchmarks for individual LRA operations:
- `startLRA` - Benchmark starting new LRAs
- `completeLRALifecycle` - Full lifecycle: start + close
- `cancelLRALifecycle` - Full lifecycle: start + cancel
- `getLRAStatus` - Status retrieval performance
- `getAllLRAs` - List all LRAs

### 2. LRABulkheadStressBenchmark
High-concurrency stress tests specifically targeting the Bulkhead mechanism:
- `mediumConcurrencyStartLRA` - 16 concurrent threads
- `highConcurrencyStartLRA` - 32 concurrent threads
- `veryHighConcurrencyStartLRA` - 64 concurrent threads (as mentioned in original issue)
- `concurrentLifecycle` - Complete lifecycles under high concurrency (32 threads)
- `rapidFireLRACycles` - Rapid state transitions (32 threads, 5 cycles each)
- `mixedConcurrentOperations` - Mixed operations: start, status check, close (24 threads)

## Prerequisites

1. **Java 17 or later**
2. **Maven 3.6+**
3. **Running LRA Coordinator** (optional - can be started automatically)
   - **Automated**: Use `START_COORDINATOR=true ./run-benchmarks.sh` to build and start a local coordinator from source
   - **Manual**: Deploy to WildFly, Quarkus, or other Jakarta EE servers
   - Default URL: `http://localhost:8080/lra-coordinator`

## Quick Start

The fastest way to run the benchmarks with a local coordinator built from source:

```bash
# Build the benchmarks
mvn clean package

# Run with automated coordinator startup
START_COORDINATOR=true ./run-benchmarks.sh
```

This will automatically:
- Build the LRA coordinator from the parent project
- Start it on Undertow (localhost:8080)
- Run all benchmarks
- Stop the coordinator when done

## Building

```bash
mvn clean package
```

This creates an executable JAR: `target/lra-benchmarks.jar`

## Running the Benchmarks

### Quick Start with Automated Coordinator

The easiest way to run benchmarks is using the provided script that automatically builds and starts a local coordinator:

```bash
./run-benchmarks.sh

# Or to force rebuild and start local coordinator:
START_COORDINATOR=true ./run-benchmarks.sh

# Run a specific benchmark with local coordinator:
START_COORDINATOR=true ./run-benchmarks.sh LRABulkheadStressBenchmark
```

The script will:
1. Build the LRA coordinator from the parent project's `coordinator` module
2. Start it on an Undertow server (default port 8080)
3. Wait for it to be ready
4. Run the benchmarks
5. Clean up the coordinator on exit

**Note:** The script automatically builds the coordinator from the main development branch in the parent directory.

### Manual Setup

#### 1. Start the LRA Coordinator

**Option A: Use the automated script**
```bash
START_COORDINATOR=true ./run-benchmarks.sh
```

**Option B: Deploy to WildFly or Quarkus**
```bash
# Deploy the coordinator WAR to WildFly
# Or use an external coordinator deployment
```

**Option C: Custom port for local coordinator**
```bash
COORDINATOR_PORT=9090 START_COORDINATOR=true ./run-benchmarks.sh
```

#### 2. Run All Benchmarks

```bash
java -jar target/lra-benchmarks.jar
```

Or using the script:
```bash
./run-benchmarks.sh
```

#### 3. Run Specific Benchmark

```bash
# Run only the Bulkhead stress tests
java -jar target/lra-benchmarks.jar LRABulkheadStressBenchmark

# Run only a specific test method
java -jar target/lra-benchmarks.jar LRABulkheadStressBenchmark.veryHighConcurrencyStartLRA

# Or via script:
./run-benchmarks.sh LRABulkheadStressBenchmark
```

#### 4. Configure Coordinator URL

```bash
java -jar target/lra-benchmarks.jar -p coordinatorUrl=http://myserver:8080/lra-coordinator

# Or via environment variable:
COORDINATOR_URL=http://myserver:9090/lra-coordinator ./run-benchmarks.sh
```

#### 5. Customize JMH Options

```bash
# Run with more iterations and longer warm-up
java -jar target/lra-benchmarks.jar -wi 5 -i 10 -f 2

# Generate results in JSON format
java -jar target/lra-benchmarks.jar -rf json -rff results.json

# List available benchmarks
java -jar target/lra-benchmarks.jar -l

# Via script with JMH options:
JMH_OPTS="-wi 5 -i 10 -rf json" ./run-benchmarks.sh
```

## Configuring the Coordinator Bulkhead

The Bulkhead configuration is set on the **coordinator deployment**, not in the benchmark client. Configure via MicroProfile Config properties:

### Using microprofile-config.properties

Add to the coordinator's `META-INF/microprofile-config.properties`:

```properties
# Set Bulkhead to 64 concurrent requests (as tested in the original issue)
io.narayana.lra.coordinator.api.Coordinator/startLRA/Bulkhead/value=64
io.narayana.lra.coordinator.api.Coordinator/startLRA/Bulkhead/waitingTaskQueue=200
```

### Using System Properties

Start the coordinator with:

```bash
-Dio.narayana.lra.coordinator.api.Coordinator/startLRA/Bulkhead/value=64
```

### Using Environment Variables (Quarkus)

```bash
export MP_FAULT_TOLERANCE_IO_NARAYANA_LRA_COORDINATOR_API_COORDINATOR_STARTLRA_BULKHEAD_VALUE=64
```

## Interpreting Results

JMH provides several metrics:

- **Throughput** (ops/ms): Operations per millisecond - higher is better
- **Average Time** (ms/op): Average time per operation - lower is better
- **Score**: The measurement value
- **Error**: Margin of error (±)

### Expected Behavior

1. **Below Bulkhead Limit**: All requests should succeed (HTTP 201)
2. **Above Bulkhead Limit**: Excess requests should be queued or rejected (HTTP 503)
3. **Success Rate**: Track via benchmark console output

The benchmarks track:
- `successCount`: Successful operations (HTTP 201/200)
- `failureCount`: Failed operations (HTTP 4xx/5xx, excluding 503)
- `rejectedCount`: Bulkhead rejections (HTTP 503)

## Copying to jbosstm/performance Repository

This module is designed to be copied to the [jbosstm/performance](https://github.com/jbosstm/performance) repository:

```bash
# 1. Clone the performance repository
git clone https://github.com/jbosstm/performance.git

# 2. Copy this entire module
cp -r lra-benchmarks performance/

# 3. Add to the parent pom.xml
# Edit performance/pom.xml and add:
#   <module>lra-benchmarks</module>

# 4. Commit and push
cd performance
git add lra-benchmarks
git commit -m "Add LRA coordinator JMH benchmarks for stress testing (fixes JBTM-3984)"
git push
```

## Common JMH Options

- `-h`: Show help
- `-l`: List available benchmarks
- `-lp`: List available parameters
- `-wi <count>`: Number of warmup iterations
- `-i <count>`: Number of measurement iterations
- `-f <count>`: Number of forks
- `-t <count>`: Number of threads
- `-to <time>`: Timeout for each iteration
- `-rf <format>`: Result format (text, csv, json, etc.)
- `-rff <filename>`: Result file name
- `-prof <profiler>`: Use a profiler (gc, stack, perf, etc.)

## Example: Profiling with GC Stats

```bash
java -jar target/lra-benchmarks.jar -prof gc
```

## Example: Running with Different Thread Counts

The `@Threads` annotation in the benchmarks controls concurrency. To run custom thread counts:

```bash
# Override thread count for all benchmarks
java -jar target/lra-benchmarks.jar -t 8

# Or modify the @Threads annotation in the source code
```

## How the Automated Coordinator Works

When you use `START_COORDINATOR=true`, the script:

1. **Builds from source**: Compiles the `coordinator` module from the parent LRA project
2. **Creates runtime**: Generates a temporary main class that starts an Undertow server
3. **Deploys coordinator**: Deploys the coordinator JAR application to Undertow
4. **Health check**: Waits up to 60 seconds for the coordinator to respond
5. **Runs benchmarks**: Executes JMH benchmarks against the local coordinator
6. **Cleanup**: Stops the coordinator process on exit (Ctrl+C or completion)

The coordinator runs in the background and logs to `/tmp/lra-coordinator.log`. The coordinator uses the same code as the unit tests - it's Undertow-based and suitable for development/benchmarking but not for production.

### Coordinator Configuration

The automated coordinator uses test-scope dependencies and default configuration. To customize:

```bash
# Use a different port
COORDINATOR_PORT=9090 START_COORDINATOR=true ./run-benchmarks.sh

# Set Bulkhead configuration (before starting coordinator)
export MP_FAULT_TOLERANCE_IO_NARAYANA_LRA_COORDINATOR_API_COORDINATOR_STARTLRA_BULKHEAD_VALUE=64
START_COORDINATOR=true ./run-benchmarks.sh
```

## Troubleshooting

### Connection Refused
- Ensure the LRA coordinator is running
- Check the coordinator URL is correct (`COORDINATOR_URL` environment variable)
- Verify firewall/network settings
- If using `START_COORDINATOR=true`, check `/tmp/lra-coordinator.log` for errors

### HTTP 503 (Service Unavailable)
- This is expected when exceeding Bulkhead limits
- Increase Bulkhead value in coordinator configuration
- Or reduce benchmark thread count

### OutOfMemoryError
- Increase JVM heap: `java -Xmx4g -jar target/lra-benchmarks.jar`
- Reduce concurrent threads in benchmarks
- Check for LRA accumulation (benchmarks should clean up)

### Slow Performance
- Ensure coordinator has sufficient resources (CPU, memory)
- Check network latency between benchmark client and coordinator
- Review coordinator logs for bottlenecks

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [MicroProfile Fault Tolerance](https://github.com/eclipse/microprofile-fault-tolerance)
- [LRA Specification](https://github.com/eclipse/microprofile-lra)
- [Narayana LRA](https://narayana.io/)

## License

Copyright The Narayana Authors  
SPDX-License-Identifier: Apache-2.0
