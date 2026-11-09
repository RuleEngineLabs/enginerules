package br.com.lucaslima.enginerules.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import br.com.lucaslima.enginerules.domain.execution.spi.ApiRequest;
import br.com.lucaslima.enginerules.domain.execution.spi.ApiResponse;

class HttpApiGatewayTest {

    private HttpServer server;
    private HttpApiGateway gateway;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json", exchange -> reply(exchange, 200, "application/json", "{\"score\": 900}"));
        server.createContext("/text", exchange -> reply(exchange, 202, "text/plain", "accepted"));
        server.createContext("/broken", exchange -> reply(exchange, 500, "application/json", "{not json"));
        server.createContext("/empty", exchange -> reply(exchange, 204, "application/json", ""));
        server.createContext("/slow", exchange -> {
            sleep(400);
            reply(exchange, 200, "text/plain", "late");
        });
        server.createContext("/echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequest.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                    + exchange.getRequestHeaders().getFirst("X-Tenant") + " " + body);
            reply(exchange, 201, "application/json", "{}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        gateway = new HttpApiGateway(Map.of("api", new HttpConnection(base, Map.of("X-Tenant", "acme")),
                "closed", new HttpConnection(URI.create("http://127.0.0.1:" + closedPort()), null)), Duration.ofSeconds(1));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void parsesJsonResponsesAndLowercasesHeaders() {
        ApiResponse response = call("GET", "/json", Map.of(), Map.of(), null);

        assertEquals(Integer.valueOf(200), response.status());
        assertEquals(Map.of("score", 900L), response.body());
        assertEquals("application/json", response.headers().get("content-type"));
    }

    @Test
    void sendsEncodedPathQueryHeadersAndJsonBody() {
        ApiResponse response = gateway.call(ApiRequest.rest("api", "POST", "/echo/{id}", Map.of("id", "a b"),
                Map.of("q", "x&y"), Map.of("doc", "1"), Duration.ofSeconds(1)));

        assertEquals(Integer.valueOf(201), response.status());
        assertEquals("POST /echo/a%20b?q=x%26y application/json acme {\"doc\":\"1\"}", lastRequest.get());
    }

    @Test
    void keepsNonJsonOrInvalidBodiesAsText() {
        assertEquals("accepted", call("GET", "/text", Map.of(), Map.of(), null).body());
        assertEquals("{not json", call("GET", "/broken", Map.of(), Map.of(), null).body());
        assertNull(call("GET", "/empty", Map.of(), Map.of(), null).body());
    }

    @Test
    void reportsTimeoutsAndTransportFailures() {
        ApiResponse slow = gateway.call(ApiRequest.rest("api", "GET", "/slow", Map.of(), Map.of(), null, Duration.ofMillis(100)));
        ApiResponse refused = gateway.call(ApiRequest.rest("closed", "GET", "/x", Map.of(), Map.of(), null, Duration.ofSeconds(1)));

        assertTrue(slow.timeout());
        assertTrue(refused.error().startsWith("ConnectException"));
    }

    @Test
    void rejectsUnknownConnectionsGrpcAndInterruptedCalls() {
        assertTrue(gateway.call(ApiRequest.rest("ghost", "GET", "/", Map.of(), Map.of(), null, Duration.ofSeconds(1))).error()
                .startsWith("unknown connection"));
        assertEquals("the HTTP gateway only serves REST calls",
                gateway.call(ApiRequest.grpc("api", "s.S", "M", null, Duration.ofSeconds(1))).error());
        Thread.currentThread().interrupt();
        try {
            ApiResponse interrupted = call("GET", "/slow", Map.of(), Map.of(), null);
            assertTrue("interrupted".equals(interrupted.error()) || interrupted.error() != null);
        } finally {
            Thread.interrupted();
        }
    }

    private ApiResponse call(String method, String path, Map<String, String> params, Map<String, String> query, Object body) {
        return gateway.call(ApiRequest.rest("api", method, path, params, query, body, Duration.ofSeconds(2)));
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
