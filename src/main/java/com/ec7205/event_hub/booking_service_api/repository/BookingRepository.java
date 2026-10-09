package com.ec7205.event_hub.booking_service_api.repository;

import com.ec7205.event_hub.booking_service_api.entity.Booking;
import com.ec7205.event_hub.booking_service_api.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    @Query("select b.id from Booking b where b.status = :status and b.reservationExpiresAt <= :now")
    List<Long> findExpiredReservationIds(@Param("status") BookingStatus status,
                                         @Param("now") LocalDateTime now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    Page<Booking> findByUserId(String userId, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"items", "payment"})
    Optional<Booking> findById(Long id);

    long countByStatus(BookingStatus status);

    long countByEventIdAndStatus(Long eventId, BookingStatus status);

    @Query("select coalesce(sum(b.totalAmount), 0) from Booking b where b.status = :status")
    BigDecimal sumTotalAmountByStatus(@Param("status") BookingStatus status);

    @Query("select coalesce(sum(b.totalAmount), 0) from Booking b where b.status = :status and b.eventId in :eventIds")
    BigDecimal sumTotalAmountByStatusAndEventIdIn(@Param("status") BookingStatus status,
                                                @Param("eventIds") List<Long> eventIds);

    Optional<Booking> findByUserIdAndIdempotencyKey(
            String userId,
            String idempotencyKey
    );
}
