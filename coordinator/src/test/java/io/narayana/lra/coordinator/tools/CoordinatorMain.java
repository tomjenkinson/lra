package io.narayana.lra.coordinator.tools;

import io.narayana.lra.coordinator.api.Coordinator;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;

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
