package com.ec7205.event_hub.booking_service_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class EventServiceApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventServiceApiApplication.class, args);
	}

}
