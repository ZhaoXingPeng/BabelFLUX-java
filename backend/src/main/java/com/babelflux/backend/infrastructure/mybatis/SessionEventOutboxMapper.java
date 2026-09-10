package com.babelflux.backend.infrastructure.mybatis;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MyBatis statements for the durable session-event outbox state machine. */
public interface SessionEventOutboxMapper {
    @Insert("""
            insert into babelflux_session_event_outbox
            (event_id, event_type, schema_version, session_id, occurred_at, payload, status, attempts, next_attempt_at)
            values (#{eventId}, #{eventType}, #{schemaVersion}, #{sessionId}, #{occurredAt}, #{payload}, 'pending', 0, #{nextAttemptAt})
            """)
    int append(@Param("eventId") String eventId, @Param("eventType") String eventType,
               @Param("schemaVersion") int schemaVersion, @Param("sessionId") String sessionId,
               @Param("occurredAt") Timestamp occurredAt, @Param("payload") String payload,
               @Param("nextAttemptAt") Timestamp nextAttemptAt);

    @Select("""
            select event_id, event_type, session_id, payload, attempts from babelflux_session_event_outbox where
            (status='pending' and next_attempt_at <= #{now})
            or (status='processing' and lease_until is not null and lease_until <= #{now})
            order by created_at limit #{limit}
            """)
    @Results(id = "pendingEvent", value = {
            @Result(column = "event_id", property = "eventId", id = true),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "session_id", property = "sessionId")
    })
    List<PendingRow> pending(@Param("now") Timestamp now, @Param("limit") int limit);

    @Update("""
            update babelflux_session_event_outbox set status='processing', lease_owner=#{owner}, lease_until=#{leaseUntil}
            where event_id=#{eventId} and ((status='pending' and next_attempt_at <= #{now})
            or (status='processing' and lease_until is not null and lease_until <= #{now}))
            """)
    int tryClaim(@Param("eventId") String eventId, @Param("owner") String owner,
                 @Param("leaseUntil") Timestamp leaseUntil, @Param("now") Timestamp now);

    @Update("""
            update babelflux_session_event_outbox set status='published', published_at=#{now}, last_error=null,
            lease_owner=null, lease_until=null where event_id=#{eventId} and status='processing' and lease_owner=#{owner}
            """)
    int markPublished(@Param("eventId") String eventId, @Param("owner") String owner, @Param("now") Timestamp now);

    @Update("""
            update babelflux_session_event_outbox set status='pending', attempts=attempts+1, next_attempt_at=#{nextAttemptAt},
            last_error=#{error}, lease_owner=null, lease_until=null where event_id=#{eventId}
            and status='processing' and lease_owner=#{owner}
            """)
    int markFailed(@Param("eventId") String eventId, @Param("owner") String owner,
                   @Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("error") String error);

    final class PendingRow {
        private String eventId;
        private String eventType;
        private String sessionId;
        private String payload;
        private int attempts;

        public String getEventId() { return eventId; }
        public void setEventId(String value) { eventId = value; }
        public String getEventType() { return eventType; }
        public void setEventType(String value) { eventType = value; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String value) { sessionId = value; }
        public String getPayload() { return payload; }
        public void setPayload(String value) { payload = value; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int value) { attempts = value; }
    }
}
