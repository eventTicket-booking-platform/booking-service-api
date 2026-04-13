package com.ec7205.event_hub.booking_service_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class BookingServiceApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(BookingServiceApiApplication.class, args);
	}

}
