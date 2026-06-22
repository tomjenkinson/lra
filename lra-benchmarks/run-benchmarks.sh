#!/bin/bash
#
# Copyright The Narayana Authors
# SPDX-License-Identifier: Apache-2.0
#
# Quick-start script for running LRA benchmarks with optional local coordinator

set -e

# Make sure background processes receive signals
set -m

# Configuration
COORDINATOR_URL="${COORDINATOR_URL:-http://localhost:8080/lra-coordinator/lra-coordinator}"
COORDINATOR_PORT="${COORDINATOR_PORT:-8080}"
BENCHMARK_CLASS="${1:-}"
START_COORDINATOR="${START_COORDINATOR:-true}"
COORDINATOR_PID=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COORDINATOR_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# JMH arguments with defaults similar to bm.sh
if [ -z "${JMHARGS}" ]; then
    JMHARGS="-r 30 -f 3 -wi 5 -i 5 -foe true"
fi

# Trap to ensure WildFly/coordinator cleanup on exit
cleanup() {
    if [ -n "$COORDINATOR_PID" ] && kill -0 $COORDINATOR_PID 2>/dev/null; then
        echo ""
        echo "Cleaning up WildFly and coordinator..."

        # Try to undeploy the coordinator WAR
        mvn -f "$SCRIPT_DIR/pom.xml" wildfly:undeploy \
            -Dwildfly.matchPattern=lra-coordinator.* \
            -Dwildfly.port=$COORDINATOR_PORT \
            > /dev/null 2>&1 || true

        # Find the actual Java process running WildFly
        JAVA_PID=$(pgrep -f "jboss-modules.jar.*jboss.http.port=$COORDINATOR_PORT" | head -1)

        if [ -n "$JAVA_PID" ]; then
            echo "Stopping WildFly Java process (PID: $JAVA_PID)..."

            # Try graceful shutdown via jboss-cli first
            if [ -n "$WILDFLY_DIR" ] && [ -f "$WILDFLY_DIR/bin/jboss-cli.sh" ]; then
                "$WILDFLY_DIR/bin/jboss-cli.sh" --connect --command=shutdown > /dev/null 2>&1 || true
                sleep 2
            fi

            # Check if still running, send TERM
            if kill -0 $JAVA_PID 2>/dev/null; then
                kill -TERM $JAVA_PID 2>/dev/null || true

                # Wait for graceful shutdown
                for i in {1..10}; do
                    if ! kill -0 $JAVA_PID 2>/dev/null; then
                        break
                    fi
                    sleep 1
                done
            fi

            # Force kill if still running
            if kill -0 $JAVA_PID 2>/dev/null; then
                echo "Force stopping WildFly Java process..."
                kill -9 $JAVA_PID 2>/dev/null || true
            fi

            echo "WildFly stopped"
        fi

        # Also clean up the shell script process
        kill -TERM $COORDINATOR_PID 2>/dev/null || true
        wait $COORDINATOR_PID 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# Ensure cleanup is called on Ctrl-C even during long operations
interrupt_handler() {
    echo ""
    echo "Interrupted by user"
    exit 130
}
trap interrupt_handler INT

echo "======================================"
echo "LRA Coordinator Benchmark Runner"
echo "======================================"
echo ""

# Function to start local coordinator using WildFly
start_local_coordinator() {
    echo "Starting local LRA coordinator with WildFly..."

    # Check if WildFly is already downloaded
    WILDFLY_DIR="$SCRIPT_DIR/target/wildfly/wildfly-"*
    if [ ! -d $WILDFLY_DIR ]; then
        echo "WildFly not found. Downloading and setting up WildFly..."
        mvn -f "$SCRIPT_DIR/pom.xml" package -Pdownload
        echo ""
    fi

    # Find the WildFly directory
    WILDFLY_DIR=$(find "$SCRIPT_DIR/target/wildfly" -maxdepth 1 -type d -name "wildfly-*" | head -n 1)
    if [ -z "$WILDFLY_DIR" ]; then
        echo "ERROR: WildFly directory not found after download"
        exit 1
    fi

    echo "Using WildFly at: $WILDFLY_DIR"

    # Start WildFly
    echo "Starting WildFly on port $COORDINATOR_PORT..."
    JBOSS_HOME="$WILDFLY_DIR" "$WILDFLY_DIR/bin/standalone.sh" \
        -Djboss.http.port=$COORDINATOR_PORT \
        > /tmp/wildfly-lra.log 2>&1 &

    COORDINATOR_PID=$!
    echo "Started WildFly with PID: $COORDINATOR_PID"
    echo "WildFly logs: /tmp/wildfly-lra.log"

    # Ensure the PID and WILDFLY_DIR are available to the trap
    export COORDINATOR_PID
    export WILDFLY_DIR

    echo "Waiting for WildFly to be ready..."

    # Wait for WildFly to be ready (up to 60 seconds)
    RETRIES=60
    WILDFLY_URL="http://localhost:$COORDINATOR_PORT"
    for i in $(seq 1 $RETRIES); do
        if curl -s -f "$WILDFLY_URL" > /dev/null 2>&1; then
            echo "WildFly is ready!"
            break
        fi
        # Check if the Java process is running (standalone.sh may exit but java process continues)
        JAVA_PID=$(pgrep -f "jboss-modules.jar.*jboss.http.port=$COORDINATOR_PORT" | head -1)
        if [ -z "$JAVA_PID" ] && ! kill -0 $COORDINATOR_PID 2>/dev/null; then
            echo ""
            echo "ERROR: WildFly process died. Check logs at /tmp/wildfly-lra.log"
            tail -30 /tmp/wildfly-lra.log
            exit 1
        fi
        if [ $i -eq $RETRIES ]; then
            echo ""
            echo "ERROR: WildFly did not start within 60 seconds"
            echo "Last 30 lines of WildFly log:"
            tail -30 /tmp/wildfly-lra.log
            exit 1
        fi
        sleep 1
        [ $((i % 5)) -eq 0 ] && echo -n "."
    done
    echo ""

    # Copy the LRA coordinator WAR to the target directory
    echo "Copying LRA coordinator WAR to target directory..."
    mvn -f "$SCRIPT_DIR/pom.xml" dependency:copy \
        -Dartifact=org.jboss.narayana.lra:lra-coordinator-war:2.0.0.Final-SNAPSHOT:war \
        -DoutputDirectory="$SCRIPT_DIR/target" \
        > /tmp/wildfly-copy.log 2>&1

    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to copy LRA coordinator"
        cat /tmp/wildfly-copy.log
        exit 1
    fi

    # Deploy the LRA coordinator WAR
    echo "Deploying LRA coordinator to WildFly..."
    mvn -f "$SCRIPT_DIR/pom.xml" wildfly:deploy \
        -Dwildfly.deployment.filename=lra-coordinator-war-2.0.0.Final-SNAPSHOT.war \
        -Dwildfly.deployment.name=lra-coordinator.war \
        -Dwildfly.hostname=localhost \
        -Dwildfly.port=9990 \
        -Dwildfly.skip=false \
        > /tmp/wildfly-deploy.log 2>&1

    if [ $? -ne 0 ]; then
        echo "ERROR: Failed to deploy LRA coordinator"
        echo "Deployment log:"
        cat /tmp/wildfly-deploy.log
        exit 1
    fi

    echo "Waiting for coordinator to be ready..."
    # Wait for coordinator to be ready (up to 30 seconds)
    RETRIES=30
    COORDINATOR_URL="http://localhost:$COORDINATOR_PORT/lra-coordinator/lra-coordinator"
    for i in $(seq 1 $RETRIES); do
        if curl -s -f "$COORDINATOR_URL" > /dev/null 2>&1; then
            echo "Coordinator is ready!"
            echo ""
            return 0
        fi
        if [ $i -eq $RETRIES ]; then
            echo ""
            echo "ERROR: Coordinator did not become available within 30 seconds"
            echo "Last 30 lines of WildFly log:"
            tail -30 /tmp/wildfly-lra.log
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
if [ ! -f "$SCRIPT_DIR/target/lra-benchmarks.jar" ]; then
    echo "Benchmark JAR not found. Building..."
    mvn -f "$SCRIPT_DIR/pom.xml" clean package
    echo ""
fi

# Test coordinator connectivity
echo "Testing coordinator at: $COORDINATOR_URL"
if ! curl -s -f "$COORDINATOR_URL" > /dev/null 2>&1; then
    echo "WARNING: Unable to connect to coordinator at $COORDINATOR_URL"
    echo ""
    echo "To start a local coordinator with WildFly, run:"
    echo "  START_COORDINATOR=true ./run-benchmarks.sh"
    echo ""
    echo "Or use Maven to automatically manage WildFly:"
    echo "  mvn verify -Pwildfly"
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
echo "JMH arguments: $JMHARGS"
echo ""

# Build JMH command
JMH_CMD="java -jar $SCRIPT_DIR/target/lra-benchmarks.jar"

# Add specific benchmark class if provided
if [ -n "$BENCHMARK_CLASS" ]; then
    JMH_CMD="$JMH_CMD $BENCHMARK_CLASS"
fi

# Add JMH arguments
JMH_CMD="$JMH_CMD $JMHARGS"

# Add coordinator URL parameter
JMH_CMD="$JMH_CMD -p coordinatorUrl=$COORDINATOR_URL"

echo "Executing: $JMH_CMD"
echo ""

# Run the benchmark
eval $JMH_CMD

echo ""
echo "======================================"
echo "Benchmark completed"
echo "======================================"
