package engineering.saint.travelbudgetoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AppTest {
    @Test
    void startsSuccessfully() {
        assertDoesNotThrow(() -> App.main(new String[0]));
    }
}
