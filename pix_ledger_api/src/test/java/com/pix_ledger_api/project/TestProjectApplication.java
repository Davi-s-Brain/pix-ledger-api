package com.pix_ledger_api.project;

import org.springframework.boot.SpringApplication;

public class TestProjectApplication {

	public static void main(String[] args) {
		SpringApplication.from(ProjectApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
