package engineering.saint.travelbudgetoptimizer;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

final class RequestAdmissionFilter extends OncePerRequestFilter {
    static final int MAX_CLIENTS = 1_024;
    static final int MAX_REQUESTS_PER_MINUTE = 20;
    static final int MAX_REQUESTS_PER_RETENTION = 100;
    static final int MAX_MCP_SESSIONS = 100;
    static final int MAX_MCP_SESSIONS_PER_CLIENT = 5;
    private static final long MINUTE_NANOS = Duration.ofMinutes(1).toNanos();
    private static final long RETENTION_NANOS = TravelOptimizationEngine.JOB_RETENTION.toNanos();
    private static final Logger logger = LoggerFactory.getLogger(RequestAdmissionFilter.class);

    private final Map<String, ClientState> clients = new HashMap<>();
    private final LongSupplier ticker;
    private final long sessionTimeoutMillis;
    private int sessions;

    RequestAdmissionFilter(long sessionTimeoutMillis) {
        this(sessionTimeoutMillis, System::nanoTime);
    }

    RequestAdmissionFilter(long sessionTimeoutMillis, LongSupplier ticker) {
        if (sessionTimeoutMillis <= 0) {
            throw new IllegalArgumentException("MCP session timeout must be positive");
        }
        this.sessionTimeoutMillis = sessionTimeoutMillis;
        this.ticker = ticker;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean mcp = path.startsWith("/mcp/");
        boolean stream = mcp && "GET".equals(request.getMethod());
        boolean submission = "POST".equals(request.getMethod())
                && (mcp || "/api/viaggi/ottimizza".equals(path));
        if (!stream && !submission) {
            chain.doFilter(request, response);
            return;
        }

        Admission admission = admit(request.getRemoteAddr(), stream);
        if (admission.client() == null) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(admission.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"" + admission.reason() + "\"}");
            return;
        }
        if (!stream) {
            chain.doFilter(request, response);
            return;
        }

        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                releaseSession(admission.client());
            }
        };
        boolean asyncTracked = false;
        try {
            chain.doFilter(request, response);
            if (request.isAsyncStarted()) {
                try {
                    var context = request.getAsyncContext();
                    context.addListener(new AsyncListener() {
                        @Override
                        public void onComplete(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onError(AsyncEvent event) {
                            release.run();
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) {
                            event.getAsyncContext().addListener(this);
                            event.getAsyncContext().setTimeout(sessionTimeoutMillis);
                        }
                    });
                    context.setTimeout(sessionTimeoutMillis);
                    asyncTracked = true;
                } catch (IllegalStateException exception) {
                    if (request.isAsyncStarted()) {
                        throw exception;
                    }
                    logger.debug("MCP stream completed before its admission listener was registered", exception);
                }
            }
        } finally {
            if (!asyncTracked) {
                release.run();
            }
        }
    }

    private synchronized Admission admit(String address, boolean stream) {
        long now = ticker.getAsLong();
        clients.values().removeIf(client -> client.sessions == 0
                && (client.requests.isEmpty() || now - client.requests.getLast() >= RETENTION_NANOS));
        ClientState client = clients.get(address);
        if (client == null) {
            if (clients.size() >= MAX_CLIENTS) {
                return new Admission(null, "Client admission capacity exceeded", 600);
            }
            client = new ClientState();
        }
        while (!client.requests.isEmpty() && now - client.requests.getFirst() >= RETENTION_NANOS) {
            client.requests.removeFirst();
        }
        long recent = client.requests.stream().filter(time -> now - time < MINUTE_NANOS).count();
        if (recent >= MAX_REQUESTS_PER_MINUTE) {
            return new Admission(null, "Request rate exceeded", 60);
        }
        if (client.requests.size() >= MAX_REQUESTS_PER_RETENTION) {
            return new Admission(null, "Ten-minute request quota exceeded", 600);
        }
        if (stream && (sessions >= MAX_MCP_SESSIONS || client.sessions >= MAX_MCP_SESSIONS_PER_CLIENT)) {
            return new Admission(null, "MCP session capacity exceeded", 60);
        }
        client.requests.addLast(now);
        clients.put(address, client);
        if (stream) {
            sessions++;
            client.sessions++;
        }
        return new Admission(client, null, 0);
    }

    private synchronized void releaseSession(ClientState client) {
        sessions--;
        client.sessions--;
    }

    private static final class ClientState {
        private final ArrayDeque<Long> requests = new ArrayDeque<>();
        private int sessions;
    }

    private record Admission(ClientState client, String reason, long retryAfterSeconds) {
    }
}
