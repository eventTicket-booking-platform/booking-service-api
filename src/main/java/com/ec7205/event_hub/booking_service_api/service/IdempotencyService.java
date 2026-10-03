package com.ec7205.event_hub.booking_service_api.service;

import com.ec7205.event_hub.booking_service_api.entity.IdempotencyRecord;
import com.ec7205.event_hub.booking_service_api.enums.IdempotencyStatus;
import com.ec7205.event_hub.booking_service_api.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(
            String userId,
            String idempotencyKey,
            String requestFingerprint
    ) {

        IdempotencyRecord record = IdempotencyRecord.builder()
                .userId(userId)
                .idempotencyKey(idempotencyKey)
                .requestFingerprint(requestFingerprint)
                .status(IdempotencyStatus.PROCESSING)
                .build();

        return repository.saveAndFlush(record);
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(
            String userId,
            String idempotencyKey
    ) {
        return repository.findByUserIdAndIdempotencyKey(
                userId,
                idempotencyKey
        );
    }

    @Transactional
    public void markCompleted(
            Long recordId,
            Long bookingId
    ) {
        IdempotencyRecord record =
                repository.findById(recordId)
                        .orElseThrow();

        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setBookingId(bookingId);

        repository.save(record);
    }

    @Transactional
    public void markFailed(Long recordId) {

        IdempotencyRecord record =
                repository.findById(recordId)
                        .orElseThrow();

        record.setStatus(IdempotencyStatus.FAILED);

        repository.save(record);
    }
}