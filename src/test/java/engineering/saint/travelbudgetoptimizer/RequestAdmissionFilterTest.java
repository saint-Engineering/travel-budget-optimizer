package engineering.saint.travelbudgetoptimizer;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RequestAdmissionFilterTest {
    private final AtomicLong now = new AtomicLong();
    private RequestAdmissionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestAdmissionFilter(120_000L, now::get);
    }

    @Test
    void restAndMcpShareMinuteQuotaWithoutTrustingForwardedHeaders() throws Exception {
        for (int i = 0; i < RequestAdmissionFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            String path = i % 2 == 0 ? "/api/viaggi/ottimizza" : "/mcp/message";
            assertEquals(200, submit("client", path).getStatus());
        }
        MockHttpServletRequest request = request("POST", "/api/viaggi/ottimizza", "client");
        request.addHeader("X-Forwarded-For", "different-client");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        verifyNoInteractions(chain);
        assertEquals(200, submit("another-client", "/api/viaggi/ottimizza").getStatus());
        now.set(Duration.ofMinutes(1).toNanos());
        assertEquals(200, submit("client", "/mcp/message").getStatus());
    }

    @Test
    void enforcesTenMinuteQuotaAndExpiresAtExactBoundary() throws Exception {
        for (int minute = 0; minute < 5; minute++) {
            now.set(Duration.ofMinutes(minute).toNanos());
            for (int i = 0; i < RequestAdmissionFilter.MAX_REQUESTS_PER_MINUTE; i++) {
                assertEquals(200, submit("client", "/api/viaggi/ottimizza").getStatus());
            }
        }
        now.set(Duration.ofMinutes(5).toNanos());
        MockHttpServletResponse blocked = submit("client", "/mcp/message");
        assertEquals(429, blocked.getStatus());
        assertEquals("600", blocked.getHeader("Retry-After"));
        now.set(Duration.ofMinutes(10).toNanos());
        assertEquals(200, submit("client", "/api/viaggi/ottimizza").getStatus());
    }

    @Test
    void boundsClientRegistryAndReclaimsInactiveClients() throws Exception {
        for (int i = 0; i < RequestAdmissionFilter.MAX_CLIENTS; i++) {
            assertEquals(200, submit("client-" + i, "/mcp/message").getStatus());
        }
        assertEquals(429, submit("overflow", "/mcp/message").getStatus());
        now.set(TravelOptimizationEngine.JOB_RETENTION.toNanos());
        assertEquals(200, submit("replacement", "/mcp/message").getStatus());
    }

    @Test
    void limitsPerClientMcpStreamsAndReleasesOnCompletion() throws Exception {
        List<MockAsyncContext> streams = new ArrayList<>();
        for (int i = 0; i < RequestAdmissionFilter.MAX_MCP_SESSIONS_PER_CLIENT; i++) {
            streams.add(open("client"));
        }
        assertEquals(429, tryOpen("client").getStatus());
        assertEquals(120_000L, streams.getFirst().getTimeout());
        streams.getFirst().complete();
        open("client");
        assertEquals(429, tryOpen("client").getStatus());
    }

    @Test
    void limitsMcpStreamsGloballyAndReleasesOnlyOnceOnTimeoutAndError() throws Exception {
        List<MockAsyncContext> streams = new ArrayList<>();
        for (int i = 0; i < RequestAdmissionFilter.MAX_MCP_SESSIONS; i++) {
            streams.add(open("client-" + i));
        }
        assertEquals(429, tryOpen("overflow").getStatus());
        MockAsyncContext first = streams.getFirst();
        for (var listener : first.getListeners()) {
            listener.onTimeout(new AsyncEvent(first));
            listener.onError(new AsyncEvent(first, new java.io.IOException("disconnected")));
            listener.onComplete(new AsyncEvent(first));
        }
        open("replacement");
        assertEquals(429, tryOpen("overflow").getStatus());
    }

    @Test
    void failedOrSynchronousMcpRequestsDoNotLeakSessionSlots() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = request("GET", "/mcp/sse", "client");
            MockHttpServletResponse response = new MockHttpServletResponse();
            if (i % 2 == 0) {
                assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response,
                        (req, res) -> { throw new IllegalStateException("transport failure"); }));
            } else {
                filter.doFilter(request, response, (req, res) -> { });
            }
        }
        for (int i = 0; i < RequestAdmissionFilter.MAX_MCP_SESSIONS_PER_CLIENT; i++) {
            open("client");
        }
    }

    @Test
    void leavesStaticResourcesAndStatusPollingOutsideSubmissionQuota() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertEquals(200, submit("client", "/api/viaggi/ottimizza-extra").getStatus());
            MockHttpServletRequest request = request("GET", "/index.html", "client");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { });
            assertEquals(200, response.getStatus());
        }
        assertEquals(200, submit("client", "/api/viaggi/ottimizza").getStatus());
    }

    @Test
    void rejectsUnlimitedSessionTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new RequestAdmissionFilter(0));
        assertThrows(IllegalArgumentException.class, () -> new RequestAdmissionFilter(-1));
    }

    @Test
    void encodedPathsCannotBypassTheQuota() throws Exception {
        for (int i = 0; i < RequestAdmissionFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            assertEquals(200, submit("client", "/api/viaggi/ottimizza").getStatus());
        }
        assertEquals(429, submit("client", "/api/viaggi/%6fttimizza").getStatus());
        assertEquals(429, submit("client", "/%6dcp/message").getStatus());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/%6dcp/sse", "client"), response, (req, res) -> req.startAsync(req, res));
        assertEquals(429, response.getStatus());
    }

    @Test
    void simultaneousRequestsCannotExceedTheQuota() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> submissions = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                submissions.add(() -> submit("client", "/api/viaggi/ottimizza").getStatus());
            }
            int allowed = 0;
            int rejected = 0;
            for (var result : workers.invokeAll(submissions)) {
                if (result.get() == 200) {
                    allowed++;
                } else {
                    assertEquals(429, result.get());
                    rejected++;
                }
            }
            assertEquals(20, allowed);
            assertEquals(20, rejected);
        }
    }

    private MockAsyncContext open(String address) throws Exception {
        MockHttpServletRequest request = request("GET", "/mcp/sse", address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> req.startAsync(req, res));
        assertEquals(200, response.getStatus());
        return (MockAsyncContext) request.getAsyncContext();
    }

    private MockHttpServletResponse tryOpen(String address) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/mcp/sse", address), response, (req, res) -> req.startAsync(req, res));
        return response;
    }

    private MockHttpServletResponse submit(String address, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", path, address), response, (req, res) -> { });
        return response;
    }

    private MockHttpServletRequest request(String method, String path, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        request.setAsyncSupported(true);
        return request;
    }
}
