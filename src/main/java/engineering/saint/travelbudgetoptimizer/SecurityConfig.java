package engineering.saint.travelbudgetoptimizer;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityConfig {
    private static final String[] PUBLIC_PATHS = {"/api/viaggi/**", "/mcp/**"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${security.admission.mcp-session-timeout-ms:120000}") long mcpSessionTimeoutMillis)
            throws Exception {
        return http
                .addFilterBefore(new RequestAdmissionFilter(mcpSessionTimeoutMillis), AuthorizationFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers(PUBLIC_PATHS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(
                                "/", "/index.html",
                                "/*.js", "/*.css", "/*.ico",
                                "/assets/**", "/media/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
