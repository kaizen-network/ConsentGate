package io.github.consentgate.core.storage;

import io.github.consentgate.core.config.RemoteStorageConfig;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteConnectionTest {
    @Test void stalledHandshakeHasABoundedWait() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var config = new RemoteStorageConfig("127.0.0.1", socket.getLocalPort(), "example", "example", "test-value", "disable", null, 250, 250);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(SQLException.class, () -> RemoteAcceptanceRepository.openConnection(config)));
        }
    }
}
