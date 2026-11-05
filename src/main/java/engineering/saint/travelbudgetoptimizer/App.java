package engineering.saint.travelbudgetoptimizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public final class App {
    private App() {
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
