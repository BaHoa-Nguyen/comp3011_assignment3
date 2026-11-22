package comp3011.assignment1;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test") // no OpenAI key needed just to check that the app starts
class Assignment1ApplicationTests {

	@Test
	void contextLoads() {
	}

}
