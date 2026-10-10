package com.movinsync.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VehicleTrackingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(VehicleTrackingServiceApplication.class, args);
	}

}
