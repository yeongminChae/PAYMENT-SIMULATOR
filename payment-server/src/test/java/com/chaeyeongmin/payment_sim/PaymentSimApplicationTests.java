package com.chaeyeongmin.payment_sim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./build/payment-sim-application-test.db")
class PaymentSimApplicationTests {

	@Test
	void contextLoads() {
	}

}
