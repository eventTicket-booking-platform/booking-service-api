package com.ec7205.event_hub.booking_service_api.client;

import com.ec7205.event_hub.booking_service_api.utils.StandardResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "auth-service-api")
public interface AuthServiceClient {

    @GetMapping("/user-service/api/v1/users/resolve-user-id")
    StandardResponseDto resolveUserIdByEmail(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestParam("email") String email
    );
}
