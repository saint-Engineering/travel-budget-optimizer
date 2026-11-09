package engineering.saint.travelbudgetoptimizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    ERRORE ERRORE ERRORE ERRORE !
    
    @Test
    void loadsServerAndMcpConfiguration() {
        assertEquals("8080", environment.getProperty("server.port"));
        assertEquals("SSE", environment.getProperty("spring.ai.mcp.server.protocol"));
        assertEquals("/mcp/sse", environment.getProperty("spring.ai.mcp.server.sse-endpoint"));
        assertEquals("/mcp/message", environment.getProperty("spring.ai.mcp.server.sse-message-endpoint"));
        assertEquals("Travel-Budget-Optimizer-Server", environment.getProperty("spring.ai.mcp.server.name"));
        assertEquals("1.0.0", environment.getProperty("spring.ai.mcp.server.version"));
        assertEquals("fake-key", environment.getProperty("cloud-tools.brave-search.api-key"));
        assertEquals("fake-key", environment.getProperty("cloud-tools.google-maps.api-key"));
        assertEquals("fake-key", environment.getProperty("cloud-tools.browserless.token"));
        assertEquals("fake-password", environment.getProperty("cloud-tools.neon-postgres.password"));
    }

    @Test
    void allowsPublicTravelAndMcpPathsIncludingPosts() throws Exception {
        mockMvc.perform(get("/api/viaggi/test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/viaggi/test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/mcp/test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/mcp/test"))
                .andExpect(status().isNotFound());
    }
}
