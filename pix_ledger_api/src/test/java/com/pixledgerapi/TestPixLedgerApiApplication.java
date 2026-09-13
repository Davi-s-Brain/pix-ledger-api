package com.pixledgerapi;

import org.springframework.boot.SpringApplication;

public class TestPixLedgerApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(PixLedgerApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}