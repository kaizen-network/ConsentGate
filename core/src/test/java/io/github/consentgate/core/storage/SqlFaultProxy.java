package io.github.consentgate.core.storage;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disposable plaintext protocol proxy for cutting only this test's connection at COMMIT. */
final class SqlFaultProxy implements AutoCloseable {
    enum Fault { BEFORE_COMMIT, COMMIT_REPLY }
    private final ServerSocket listener;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean armed = new AtomicBoolean();
    private final CountDownLatch cut = new CountDownLatch(1);
    private final Fault fault;
    private volatile boolean committedReply;

    SqlFaultProxy(String host, int port, Fault fault) throws IOException {
        InetAddress target = InetAddress.getByName(host);
        if (!target.isLoopbackAddress()) throw new IllegalArgumentException("Socket fault tests require a loopback database");
        this.fault = fault;
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers.submit(() -> {
            try {
                while (!listener.isClosed()) {
                    Socket client = listener.accept();
                    sockets.add(client);
                    Socket server = new Socket();
                    sockets.add(server);
                    server.connect(new java.net.InetSocketAddress(target, port), 3000);
                    client.setSoTimeout(10000);
                    server.setSoTimeout(10000);
                    var waiting = new AtomicBoolean();
                    workers.submit(() -> forward(client, server, true, waiting));
                    workers.submit(() -> forward(server, client, false, waiting));
                }
            } catch (IOException ignored) { }
        });
    }

    int port() { return listener.getLocalPort(); }
    void arm() { armed.set(true); }
    boolean wasCut() throws InterruptedException { return cut.await(3, TimeUnit.SECONDS); }
    boolean sawCommittedReply() { return committedReply; }

    private void forward(Socket source, Socket destination, boolean outbound, AtomicBoolean waiting) {
        try {
            var input = source.getInputStream();
            var output = destination.getOutputStream();
            while (true) {
                byte[] header = input.readNBytes(4);
                if (header.length != 4) throw new EOFException();
                int length = (header[0] & 255) | ((header[1] & 255) << 8) | ((header[2] & 255) << 16);
                byte[] body = input.readNBytes(length);
                if (body.length != length) throw new EOFException();
                if (outbound && isCommit(body) && armed.compareAndSet(true, false)) {
                    if (fault == Fault.BEFORE_COMMIT) { cut.countDown(); return; }
                    waiting.set(true);
                } else if (!outbound && waiting.compareAndSet(true, false)) {
                    committedReply = body.length > 0 && body[0] == 0;
                    cut.countDown();
                    return;
                }
                output.write(header);
                output.write(body);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            close(source);
            close(destination);
        }
    }

    private static boolean isCommit(byte[] body) {
        if (body.length < 2 || body[0] != 3) return false;
        int start = 1;
        // CLIENT_QUERY_ATTRIBUTES adds zero parameters and one parameter set before the SQL text.
        if (body.length > 3 && body[1] == 0 && body[2] == 1) start = 3;
        return new String(body, start, body.length - start, StandardCharsets.UTF_8).trim().equalsIgnoreCase("COMMIT");
    }

    private void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
        sockets.remove(socket);
    }

    @Override public void close() throws Exception {
        listener.close();
        sockets.forEach(this::close);
        workers.shutdownNow();
        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Socket proxy did not stop");
    }
}
