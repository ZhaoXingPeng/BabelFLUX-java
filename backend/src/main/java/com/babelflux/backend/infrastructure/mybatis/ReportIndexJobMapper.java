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

/** MyBatis statements for durable Elasticsearch indexing jobs. */
public interface ReportIndexJobMapper {
    @Update("""
            update babelflux_report_index_jobs set payload=#{payload}, status='pending', attempts=0,
            next_attempt_at=#{now}, last_error=null, lease_owner=null, lease_until=null, updated_at=#{now}
            where report_id=#{reportId}
            """)
    int resetPending(@Param("reportId") String reportId, @Param("payload") String payload, @Param("now") Timestamp now);

    @Insert("""
            insert into babelflux_report_index_jobs
            (report_id, payload, status, attempts, next_attempt_at, updated_at)
            values (#{reportId}, #{payload}, 'pending', 0, #{now}, #{now})
            """)
    int insertPending(@Param("reportId") String reportId, @Param("payload") String payload, @Param("now") Timestamp now);

    @Select("""
            select report_id, payload, attempts from babelflux_report_index_jobs where
            (status='pending' and next_attempt_at <= #{now})
            or (status='processing' and lease_until is not null and lease_until <= #{now})
            order by updated_at limit #{limit}
            """)
    @Results(id = "pendingJob", value = {
            @Result(column = "report_id", property = "reportId", id = true)
    })
    List<PendingRow> pending(@Param("now") Timestamp now, @Param("limit") int limit);

    @Update("""
            update babelflux_report_index_jobs set status='processing', lease_owner=#{owner}, lease_until=#{leaseUntil}
            where report_id=#{reportId} and ((status='pending' and next_attempt_at <= #{now})
            or (status='processing' and lease_until is not null and lease_until <= #{now}))
            """)
    int tryClaim(@Param("reportId") String reportId, @Param("owner") String owner,
                 @Param("leaseUntil") Timestamp leaseUntil, @Param("now") Timestamp now);

    @Select("""
            select report_id, status, attempts, next_attempt_at, last_error, updated_at
            from babelflux_report_index_jobs where report_id=#{reportId}
            """)
    @Results(id = "jobStatus", value = {
            @Result(column = "report_id", property = "reportId", id = true),
            @Result(column = "next_attempt_at", property = "nextAttemptAt"),
            @Result(column = "last_error", property = "lastError"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<StatusRow> status(@Param("reportId") String reportId);

    @Update("""
            update babelflux_report_index_jobs set status='indexed', updated_at=#{now}, last_error=null,
            lease_owner=null, lease_until=null where report_id=#{reportId} and status='processing' and lease_owner=#{owner}
            """)
    int markIndexed(@Param("reportId") String reportId, @Param("owner") String owner, @Param("now") Timestamp now);

    @Update("""
            update babelflux_report_index_jobs set status='pending', attempts=attempts+1, next_attempt_at=#{nextAttemptAt},
            last_error=#{error}, lease_owner=null, lease_until=null, updated_at=#{updatedAt} where report_id=#{reportId}
            and status='processing' and lease_owner=#{owner}
            """)
    int markFailed(@Param("reportId") String reportId, @Param("owner") String owner,
                   @Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("error") String error,
                   @Param("updatedAt") Timestamp updatedAt);

    final class PendingRow {
        private String reportId;
        private String payload;
        private int attempts;

        public String getReportId() { return reportId; }
        public void setReportId(String value) { reportId = value; }
        public String getPayload() { return payload; }
        public void setPayload(String value) { payload = value; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int value) { attempts = value; }
    }

    final class StatusRow {
        private String reportId;
        private String status;
        private int attempts;
        private Timestamp nextAttemptAt;
        private String lastError;
        private Timestamp updatedAt;

        public String getReportId() { return reportId; }
        public void setReportId(String value) { reportId = value; }
        public String getStatus() { return status; }
        public void setStatus(String value) { status = value; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int value) { attempts = value; }
        public Timestamp getNextAttemptAt() { return nextAttemptAt; }
        public void setNextAttemptAt(Timestamp value) { nextAttemptAt = value; }
        public String getLastError() { return lastError; }
        public void setLastError(String value) { lastError = value; }
        public Timestamp getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Timestamp value) { updatedAt = value; }
    }
}
