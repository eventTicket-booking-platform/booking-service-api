# Booking Service API

Booking and booking-admin service for Event Hub. This service creates bookings, returns customer booking history, exposes admin booking views and stats, and provides internal booking counts for other services.

## Stack

- Java 17
- Spring Boot 3
- Spring Security OAuth2 resource server
- Spring Data JPA
- Spring Cloud Config client
- Eureka client
- OpenFeign
- MySQL
- RabbitMQ

## Functional Requirements

- Create bookings for authenticated users
- Return current user booking history
- Return booking detail for authorized user, admin, or host access
- Expose admin booking list with filters
- Expose booking statistics for admin dashboards
- Resolve event metadata from the event service
- Resolve user metadata from the auth service
- Publish notification events to RabbitMQ
- Return confirmed booking counts for internal consumers

## Non-Functional Requirements

- Stateless JWT authentication
- Role-aware admin access for management endpoints
- Config Server based configuration
- Eureka registration
- MySQL persistence
- RabbitMQ integration for async notification workflows
- Inter-service calls through Feign clients
- Actuator health support

## APIs

Base path: `/booking-service/api/v1`

Authenticated user endpoints:

- `POST /bookings`
- `GET /bookings/my`
- `GET /bookings/{bookingId}`

Admin or host endpoints:

- `GET /admin/all`
- `GET /admin/stats`

Internal endpoints:

- `GET /bookings/internal/event/{eventId}/count`

## Role-Based Access

- `permitAll`: `/bookings/internal/**`
- `admin`, `host`: `/admin/**`
- any authenticated user: booking creation and own booking views

Access to individual booking detail is finalized in service logic using the authenticated user ID and role.

## Runtime Dependencies

- Config Server on `8888`
- Eureka Server on `8761`
- MySQL
- RabbitMQ
- Event service
- Auth service

## Local Setup

1. Copy `.env.example` to `.env`.
2. Fill:
   - `SPRING_CLOUD_CONFIG_URI`
   - `BOOKING_DB_PASSWORD`
   - `RABBITMQ_PASSWORD`
3. Start:
   - `config-server`
   - `eureka-server`
   - MySQL
   - RabbitMQ
   - `auth-service-api`
   - `event-service-api`
4. Run:

```powershell
.\mvnw.cmd spring-boot:run
```

Default port: `9093`

## Build

```powershell
.\mvnw.cmd clean package
```

## Notes

- The current backend exposes create, list, and detail endpoints. A booking cancel endpoint is not present in this service code.
