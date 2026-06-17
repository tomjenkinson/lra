#!/bin/bash
#
# Copyright The Narayana Authors
# SPDX-License-Identifier: Apache-2.0
#
# Quick-start script for running LRA benchmarks with optional local coordinator

set -e

# Configuration
COORDINATOR_URL="${COORDINATOR_URL:-http://localhost:8080/lra-coordinator}"
COORDINATOR_PORT="${COORDINATOR_PORT:-8080}"
BENCHMARK_CLASS="${1:-}"
JMH_OPTS="${JMH_OPTS:-}"
START_COORDINATOR="${START_COORDINATOR:-false}"
COORDINATOR_PID=""
COORDINATOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Trap to ensure coordinator cleanup on exit
cleanup() {
    if [ -n "$COORDINATOR_PID" ]; then
        echo ""
        echo "Stopping local coordinator (PID: $COORDINATOR_PID)..."
        kill $COORDINATOR_PID 2>/dev/null || true
        wait $COORDINATOR_PID 2>/dev/null || true
        echo "Coordinator stopped"
    fi
}
trap cleanup EXIT INT TERM

echo "======================================"
echo "LRA Coordinator Benchmark Runner"
echo "======================================"
echo ""

# Function to start local coordinator using Maven exec plugin
start_local_coordinator() {
    echo "Starting local LRA coordinator from source..."

    # Build the coordinator module if needed
    if [ ! -f "$COORDINATOR_DIR/coordinator/target/classes/io/narayana/lra/coordinator/api/Coordinator.class" ]; then
        echo "Building LRA coordinator..."
        cd "$COORDINATOR_DIR"
        mvn clean install -DskipTests -pl coordinator -am
        cd - > /dev/null
        echo ""
    fi

    echo "Starting coordinator on port $COORDINATOR_PORT..."

    # Start coordinator using a simple test-based approach
    cd "$COORDINATOR_DIR/coordinator"

    # Create a temporary main class to run the coordinator
    RUNNER_CLASS="io.narayana.lra.coordinator.tools.CoordinatorMain"
    RUNNER_FILE="src/test/java/io/narayana/lra/coordinator/tools/CoordinatorMain.java"

    mkdir -p "src/test/java/io/narayana/lra/coordinator/tools"

    cat > "$RUNNER_FILE" <<'EOF'
package io.narayana.lra.coordinator.tools;

import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;

import java.util.HashSet;
import java.util.Set;

public class CoordinatorMain {
    @ApplicationPath("/")
    public static class LRACoordinatorApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            HashSet<Class<?>> classes = new HashSet<>();
            classes.add(Coordinator.class);
            return classes;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String host = "localhost";

        LRALogger.logger.infof("Starting LRA Coordinator on http://%s:%d/lra-coordinator", host, port);

        UndertowJaxrsServer server = new UndertowJaxrsServer()
                .setHostname(host)
                .setPort(port);

        server.start();
        server.deploy(LRACoordinatorApplication.class);

        LRALogger.logger.info("LRA Coordinator started successfully. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
EOF

    # Compile and run the coordinator
    mvn test-compile exec:java \
        -Dexec.mainClass="$RUNNER_CLASS" \
        -Dexec.args="$COORDINATOR_PORT" \
        -Dexec.classpathScope="test" \
        > /tmp/lra-coordinator.log 2>&1 &

    COORDINATOR_PID=$!
    cd - > /dev/null

    echo "Started coordinator with PID: $COORDINATOR_PID"
    echo "Coordinator logs: /tmp/lra-coordinator.log"
    echo "Waiting for coordinator to be ready..."

    # Wait for coordinator to be ready (up to 30 seconds)
    RETRIES=60
    COORDINATOR_URL="http://localhost:$COORDINATOR_PORT/lra-coordinator"
    for i in $(seq 1 $RETRIES); do
        if curl -s -f "$COORDINATOR_URL" > /dev/null 2>&1; then
            echo "Coordinator is ready!"
            echo ""
            return 0
        fi
        if ! kill -0 $COORDINATOR_PID 2>/dev/null; then
            echo ""
            echo "ERROR: Coordinator process died. Check logs at /tmp/lra-coordinator.log"
            tail -20 /tmp/lra-coordinator.log
            exit 1
        fi
        if [ $i -eq $RETRIES ]; then
            echo ""
            echo "ERROR: Coordinator did not start within 60 seconds"
            echo "Last 20 lines of coordinator log:"
            tail -20 /tmp/lra-coordinator.log
            exit 1
        fi
        sleep 1
        [ $((i % 5)) -eq 0 ] && echo -n "."
    done
    echo ""
}

# Check if we should start a local coordinator
if [ "$START_COORDINATOR" = "true" ]; then
    start_local_coordinator
fi

# Check if benchmark JAR exists
if [ ! -f "target/lra-benchmarks.jar" ]; then
    echo "Benchmark JAR not found. Building..."
    mvn clean package
    echo ""
fi

# Test coordinator connectivity
echo "Testing coordinator at: $COORDINATOR_URL"
if ! curl -s -f "$COORDINATOR_URL" > /dev/null 2>&1; then
    echo "WARNING: Unable to connect to coordinator at $COORDINATOR_URL"
    echo ""
    echo "To start a local coordinator, run:"
    echo "  START_COORDINATOR=true ./run-benchmarks.sh"
    echo ""
    echo "Or ensure an external coordinator is running at $COORDINATOR_URL"
    echo ""
    if [ "$START_COORDINATOR" != "true" ]; then
        read -p "Continue anyway? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
else
    echo "✓ Coordinator is reachable"
fi

echo ""
echo "======================================"
echo "Running Benchmarks"
echo "======================================"
echo "Coordinator URL: $COORDINATOR_URL"
echo "Benchmark class: ${BENCHMARK_CLASS:-all}"
echo ""

# Build JMH command
JMH_CMD="java -jar target/lra-benchmarks.jar"

# Add coordinator URL parameter
JMH_CMD="$JMH_CMD -p coordinatorUrl=$COORDINATOR_URL"

# Add specific benchmark class if provided
if [ -n "$BENCHMARK_CLASS" ]; then
    JMH_CMD="$JMH_CMD $BENCHMARK_CLASS"
fi

# Add additional JMH options
if [ -n "$JMH_OPTS" ]; then
    JMH_CMD="$JMH_CMD $JMH_OPTS"
fi

echo "Executing: $JMH_CMD"
echo ""

# Run the benchmark
eval $JMH_CMD

echo ""
echo "======================================"
echo "Benchmark completed"
echo "======================================"
