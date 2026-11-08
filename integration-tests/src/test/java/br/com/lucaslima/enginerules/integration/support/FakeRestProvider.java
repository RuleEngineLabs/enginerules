package br.com.lucaslima.enginerules.integration.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.lucaslima.enginerules.adapter.json.Json;

public final class FakeRestProvider implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final List<Recorded> received = new CopyOnWriteArrayList<>();

    private FakeRestProvider(HttpServer server) {
        this.server = server;
    }

    public static FakeRestProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeRestProvider provider = new FakeRestProvider(server);
            server.createContext("/", provider::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return provider;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    public void respond(String method, String path, Reply... replies) {
        scripts.put(key(method, path), new Script(List.of(replies)));
    }

    public int calls(String method, String path) {
        return (int) received.stream().filter(request -> request.method().equals(method) && request.path().equals(path)).count();
    }

    public List<Recorded> requests(String path) {
        return received.stream().filter(request -> request.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Recorded(method, path, exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("X-Tenant"), body.isBlank() ? null : Json.parse(body)));
            Script script = scripts.get(key(method, path));
            Reply reply = script == null ? new Reply(404, Map.of("error", "no stub for " + method + " " + path), 0) : script.next();
            pause(reply.delayMillis());
            byte[] payload = reply.body() == null ? new byte[0] : Json.write(reply.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
                }
            }
        } catch (IOException clientWentAway) {
            received.add(new Recorded("ABORTED", exchange.getRequestURI().getPath(), null, null, null));
        }
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    public record Reply(int status, Object body, long delayMillis) {

        public static Reply ok(Object body) {
            return new Reply(200, body, 0);
        }

        public static Reply status(int status, Object body) {
            return new Reply(status, body, 0);
        }

        public static Reply slow(long delayMillis, Object body) {
            return new Reply(200, body, delayMillis);
        }
    }

    public record Recorded(String method, String path, String query, String tenant, Object body) {
    }

    private static final class Script {

        private final List<Reply> replies;
        private final AtomicInteger served = new AtomicInteger();

        private Script(List<Reply> replies) {
            this.replies = new ArrayList<>(replies);
        }

        private Reply next() {
            int index = served.getAndIncrement();
            return replies.get(Math.min(index, replies.size() - 1));
        }
    }
}
