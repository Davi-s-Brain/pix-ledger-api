package com.pixledgerapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(classes = PixLedgerApiApplication.class)
@ActiveProfiles("test")
class PixLedgerApiApplicationTests {

	@Test
	void contextLoads() {
	}

}