package engineering.saint.travelbudgetoptimizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpSecurityIntegrationTest {
    @Autowired
    private TestRestTemplate http;

    @Test
    void preservesApiErrorStatusesThroughRealServletDispatch() {
        assertEquals(HttpStatus.NOT_FOUND,
                http.getForEntity("/api/viaggi/status/unknown-job", String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                http.postForEntity("/api/viaggi/ottimizza",
                        Map.of("partenza", "x".repeat(257), "destinazione", "Milano", "budgetMassimo", 100),
                        String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                http.getForEntity("/private", String.class).getStatusCode());
    }
}
