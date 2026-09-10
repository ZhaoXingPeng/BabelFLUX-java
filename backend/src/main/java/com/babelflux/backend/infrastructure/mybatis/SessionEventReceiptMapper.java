package com.babelflux.backend.infrastructure.mybatis;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** MyBatis statement used by the RabbitMQ consumer's idempotency receipt. */
public interface SessionEventReceiptMapper {
    @Insert("""
            insert into babelflux_session_event_receipts (event_id, event_type, session_id, received_at)
            values (#{eventId}, #{eventType}, #{sessionId}, #{receivedAt})
            """)
    int record(@Param("eventId") String eventId, @Param("eventType") String eventType,
               @Param("sessionId") String sessionId, @Param("receivedAt") Timestamp receivedAt);
}
