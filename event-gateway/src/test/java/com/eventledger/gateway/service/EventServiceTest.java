package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.model.dto.EventRequest;
import com.eventledger.gateway.model.dto.EventResponse;
import com.eventledger.gateway.model.dto.EventSubmitResult;
import com.eventledger.gateway.model.entity.EventEntity;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private AccountServiceClient accountServiceClient;
    @Mock private MetricsService metricsService;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        eventService = new EventService(eventRepository, accountServiceClient, mapper, metricsService);
    }

    @Test
    void submitEvent_newEvent_savesPendingThenProcessed() {
        when(eventRepository.findById("evt-001")).thenReturn(Optional.empty());
        // Capture status at each save() invocation — the entity is mutated in-place
        // between saves, so ArgumentCaptor.getAllValues() would show the same final
        // status for both captured references. Snapshot via thenAnswer instead.
        List<EventStatus> savedStatuses = new ArrayList<>();
        when(eventRepository.save(any())).thenAnswer(inv -> {
            savedStatuses.add(inv.<EventEntity>getArgument(0).getStatus());
            return inv.getArgument(0);
        });

        EventSubmitResult result = eventService.submitEvent(validRequest("evt-001"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.event().eventId()).isEqualTo("evt-001");
        assertThat(result.event().status()).isEqualTo(EventStatus.PROCESSED);
        assertThat(savedStatuses).containsExactly(EventStatus.PENDING, EventStatus.PROCESSED);
    }

    @Test
    void submitEvent_duplicateEventId_neitherWriteNorClientCall() {
        EventEntity existing = entityWith("evt-dup", EventStatus.PROCESSED);
        when(eventRepository.findById("evt-dup")).thenReturn(Optional.of(existing));

        EventSubmitResult result = eventService.submitEvent(validRequest("evt-dup"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.event().eventId()).isEqualTo("evt-dup");
        verify(eventRepository, never()).save(any());
        verify(accountServiceClient, never()).applyTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitEvent_accountServiceFails_savesFailedStatusAndRethrows() {
        when(eventRepository.findById("evt-fail")).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new AccountServiceUnavailableException("down"))
                .when(accountServiceClient).applyTransaction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> eventService.submitEvent(validRequest("evt-fail")))
                .isInstanceOf(AccountServiceUnavailableException.class);

        ArgumentCaptor<EventEntity> captor = ArgumentCaptor.forClass(EventEntity.class);
        verify(eventRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(EventStatus.FAILED);
    }

    @Test
    void submitEvent_withMetadata_roundTripsMetadataMap() {
        Map<String, Object> metadata = Map.of("region", "EU", "priority", 1);
        EventRequest req = new EventRequest("evt-meta", "acc-1", "CREDIT",
                BigDecimal.TEN, "EUR", Instant.now(), metadata);
        when(eventRepository.findById("evt-meta")).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.event().metadata()).containsEntry("region", "EU");
    }

    @Test
    void getEventById_found_returnsCorrectFields() {
        when(eventRepository.findById("evt-x"))
                .thenReturn(Optional.of(entityWith("evt-x", EventStatus.PROCESSED)));

        EventResponse resp = eventService.getEventById("evt-x");

        assertThat(resp.eventId()).isEqualTo("evt-x");
        assertThat(resp.status()).isEqualTo(EventStatus.PROCESSED);
    }

    @Test
    void getEventById_notFound_throwsNoSuchElement() {
        when(eventRepository.findById("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEventById("gone"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("gone");
    }

    @Test
    void getEventsByAccount_noEvents_returnsEmptyList() {
        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acc-empty"))
                .thenReturn(List.of());

        assertThat(eventService.getEventsByAccount("acc-empty")).isEmpty();
    }

    @Test
    void getEventsByAccount_delegatesOrderingToRepository() {
        Instant t1 = Instant.parse("2026-01-01T08:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:00:00Z");
        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acc-1"))
                .thenReturn(List.of(entityWithTimestamp("evt-a", t1), entityWithTimestamp("evt-b", t2)));

        List<EventResponse> result = eventService.getEventsByAccount("acc-1");

        assertThat(result).extracting(EventResponse::eventId).containsExactly("evt-a", "evt-b");
        assertThat(result).extracting(EventResponse::eventTimestamp).containsExactly(t1, t2);
    }

    // --- helpers ---

    private EventRequest validRequest(String eventId) {
        return new EventRequest(eventId, "acc-1", "CREDIT",
                BigDecimal.valueOf(50), "USD", Instant.now(), null);
    }

    private EventEntity entityWith(String eventId, EventStatus status) {
        EventEntity e = new EventEntity(eventId, "acc-1", "CREDIT",
                BigDecimal.valueOf(50), "USD", Instant.now(), null, Instant.now(), EventStatus.PENDING);
        e.setStatus(status);
        return e;
    }

    private EventEntity entityWithTimestamp(String eventId, Instant ts) {
        return new EventEntity(eventId, "acc-1", "CREDIT",
                BigDecimal.valueOf(50), "USD", ts, null, Instant.now(), EventStatus.PROCESSED);
    }
}
