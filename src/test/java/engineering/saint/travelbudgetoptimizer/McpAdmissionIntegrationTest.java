package engineering.saint.travelbudgetoptimizer;

import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.admission.mcp-session-timeout-ms=2000")
class McpAdmissionIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private WebMvcSseServerTransportProvider transport;

    @Test
    void capsRealMcpSessionsAndExpiresTransportSessionsWithoutChangingUiAccess() throws Exception {
        ArrayList<InputStream> streams = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            try {
                for (int i = 0; i < RequestAdmissionFilter.MAX_MCP_SESSIONS_PER_CLIENT; i++) {
                    HttpResponse<InputStream> response = client.send(request("/mcp/sse"),
                            HttpResponse.BodyHandlers.ofInputStream());
                    streams.add(response.body());
                    assertEquals(200, response.statusCode());
                }
                HttpResponse<String> blocked = client.send(request("/mcp/sse"),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(429, blocked.statusCode());
                assertTrue(blocked.headers().firstValue("Retry-After").isPresent());
                assertEquals(5, sessions().size());

                for (InputStream stream : streams) {
                    String body = CompletableFuture.supplyAsync(() -> {
                        try {
                            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException exception) {
                            throw new CompletionException(exception);
                        }
                    }).get(10, TimeUnit.SECONDS);
                    assertTrue(body.contains("event:endpoint"));
                }
                assertTrue(sessions().isEmpty());
                HttpResponse<InputStream> replacement = client.send(request("/mcp/sse"),
                        HttpResponse.BodyHandlers.ofInputStream());
                streams.add(replacement.body());
                assertEquals(200, replacement.statusCode());
                assertEquals(200, client.send(request("/"), HttpResponse.BodyHandlers.ofString()).statusCode());
                for (int i = 0; i < RequestAdmissionFilter.MAX_REQUESTS_PER_MINUTE - 6; i++) {
                    HttpRequest invalid = HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/api/viaggi/ottimizza"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                    assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                HttpRequest throttled = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/mcp/message"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                assertEquals(429, client.send(throttled, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, client.send(request("/"), HttpResponse.BodyHandlers.ofString()).statusCode());
            } finally {
                for (InputStream stream : streams) {
                    stream.close();
                }
            }
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
    }

    private Map<?, ?> sessions() {
        return (Map<?, ?>) ReflectionTestUtils.getField(transport, "sessions");
    }
}
