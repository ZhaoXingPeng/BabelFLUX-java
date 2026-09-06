package com.babelflux.backend.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.babelflux.backend.service.SessionTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class RedisSessionRepositoryTest {
    @Test
    void storesHashedTokenKeysAndPreservesTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisSessionRepository repository = new RedisSessionRepository(redis, mapper());
        SessionTokenService.HandoffTicket ticket = new SessionTokenService.HandoffTicket(
                "h_secret", "session-1", "microphone", "en", "zh", "bilingual",
                Instant.parse("2026-09-07T00:05:00Z"));

        repository.saveHandoff(ticket, Duration.ofMinutes(5));

        verify(values, times(1)).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));
        verify(values, times(1)).set(anyString(), anyString(), eq(Duration.ofMinutes(6)));
        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).set(key.capture(), anyString(), any(Duration.class));
        assertTrue(key.getAllValues().stream().noneMatch(value -> value.contains("h_secret")));
    }

    @Test
    void mapsAtomicClaimResultAndRestoresTicket() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SessionTokenService.HandoffTicket ticket = new SessionTokenService.HandoffTicket(
                "h_secret", "session-1", null, "en", "zh", "bilingual",
                Instant.parse("2026-09-07T00:05:00Z"));
        String encoded = mapper().writeValueAsString(ticket);
        when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("__CLAIMED__" + encoded);
        RedisSessionRepository repository = new RedisSessionRepository(redis, mapper());

        RedisSessionRepository.ClaimResult result = repository.claimHandoff("h_secret");

        assertEquals(RedisSessionRepository.Status.CLAIMED, result.status());
        assertEquals(ticket, result.ticket());
    }

    @Test
    void mapsUsedExpiredAndNotFoundStatuses() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisSessionRepository repository = new RedisSessionRepository(redis, mapper());
        for (String response : new String[]{"__USED__", "__EXPIRED__", "__NOT_FOUND__"}) {
            when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn(response);
            RedisSessionRepository.Status expected = switch (response) {
                case "__USED__" -> RedisSessionRepository.Status.USED;
                case "__EXPIRED__" -> RedisSessionRepository.Status.EXPIRED;
                default -> RedisSessionRepository.Status.NOT_FOUND;
            };
            assertEquals(expected,
                    repository.claimHandoff("h_" + response).status());
        }
    }

    @Test
    void readsWebSocketTicketFromRedis() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        SessionTokenService.WebSocketTicket ticket = new SessionTokenService.WebSocketTicket(
                "w_secret", "session-1", Instant.parse("2026-09-07T01:00:00Z"));
        when(values.get(anyString())).thenReturn(mapper().writeValueAsString(ticket));
        RedisSessionRepository repository = new RedisSessionRepository(redis, mapper());

        Optional<SessionTokenService.WebSocketTicket> result = repository.findWebSocket("w_secret");

        assertEquals(Optional.of(ticket), result);
    }

    private static ObjectMapper mapper() { return JsonMapper.builder().addModule(new JavaTimeModule()).build(); }
}
