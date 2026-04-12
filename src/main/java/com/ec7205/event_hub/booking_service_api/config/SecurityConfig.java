package com.ec7205.event_hub.booking_service_api.config;

//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
//import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
//import org.springframework.security.config.http.SessionCreationPolicy;
//import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
//import org.springframework.security.web.SecurityFilterChain;

//@Configuration
//@EnableMethodSecurity
public class SecurityConfig {

//    @Bean
//    public SecurityFilterChain securityFilterChain(
//            HttpSecurity http
//    ) throws Exception {
//        http.csrf(AbstractHttpConfigurer::disable);
//        http.authorizeHttpRequests(autherize -> autherize
//                        .requestMatchers("/booking-service/api/v1/user/**").hasRole("user")
//                        .requestMatchers("/booking-service/api/v1/host/**").hasRole("host")
//                        .requestMatchers("/booking-service/api/v1/admin/**").hasRole("admin")
//                        .requestMatchers("/booking-service/api/v1/internal/**").authenticated()
//                        .anyRequest().authenticated()
//                )
//        http.oauth2ResourceServer(t->{
//            t.jwt(jwtConfigurer->jwtConfigurer.jwtAuthenticationConverter(authConverter));
//        });
//
//        http.sessionManagement(t->t.sessionCreationPolicy(SessionCreationPolicy.STATELESS)); // TASK 1
//        return http.build();
//
//        return http.build();
//    }
//
//    @Bean
//    public DefaultMethodSecurityExpressionHandler msecurity(){
//        DefaultMethodSecurityExpressionHandler defaultMethodSecurityExpressionHandler = new DefaultMethodSecurityExpressionHandler();
//        defaultMethodSecurityExpressionHandler.setDefaultRolePrefix("");
//        return defaultMethodSecurityExpressionHandler;
//    }
}
