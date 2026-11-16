package com.jovx.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic
public class JovxApiApplication {

	public static void main(String[] args) {
		// Keep JDBC timestamp handling consistent when launched directly from the IDE.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
		SpringApplication.run(JovxApiApplication.class, args);
	}

}
