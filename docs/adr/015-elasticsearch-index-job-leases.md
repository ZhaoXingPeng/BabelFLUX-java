# ADR 015: Elasticsearch Report Index Job Leases

## Context

MySQL is the source of truth for report snapshots. Elasticsearch contains a
rebuildable search projection. Without a claim, multiple application instances
can select the same pending job, write the same document concurrently, and let
an older failure update overwrite a newer success.

## Decision

Each `babelflux_report_index_jobs` row follows this state machine:

```text
pending --claim(owner, lease_until)--> processing --success(owner)--> indexed
                                      \--failure(owner)--> pending
```

`tryClaim` uses a conditional update to make normal processing mutually
exclusive. An expired lease may be claimed by another instance. Completion and
failure updates require the current owner, so a stale worker cannot overwrite
newer work. Enqueueing a new version of a report clears a previous lease.

## Consequences

- Processing is at-least-once, not exactly-once. Elasticsearch document IDs
  remain stable so repeated writes are idempotent.
- A worker can still finish an Elasticsearch write after its lease expires, but
  its stale state transition is ignored. The 60-second lease must exceed normal
  Elasticsearch request latency.
- The nullable lease columns are supplied by the portable additive MySQL/H2
  migration already used by the application; no vendor-specific schema SQL is
  added here.

## Verification

- `JdbcReportIndexJobStoreTest` verifies claim exclusion, expired-lease
  recovery, and stale-owner protection.
- Real dual-instance Elasticsearch evidence is retained on superseded PR #28;
  the replacement PR repeats the relevant runtime checks before merge.

## Rollback

Reverting the application code restores the previous single-instance behavior.
The nullable lease columns are backward compatible and do not require a
destructive schema rollback.
