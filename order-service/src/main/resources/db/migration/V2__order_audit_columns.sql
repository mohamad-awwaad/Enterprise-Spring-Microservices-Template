-- Adds Spring Data JPA auditing support (@CreatedDate/@LastModifiedDate on OrderEntity, backed
-- by java.time.Instant instead of LocalDateTime) and optimistic locking (@Version) to orders.

-- creation_time/update_time move from a naive TIMESTAMP to a TIMESTAMPTZ so the stored instant
-- carries an explicit UTC offset, matching the LocalDateTime -> Instant column type change.
-- Existing rows were always written in UTC (the application never set another zone), so
-- reinterpreting them "AT TIME ZONE 'UTC'" preserves their original instant.
ALTER TABLE orders
    ALTER COLUMN creation_time TYPE timestamp(6) with time zone USING creation_time AT TIME ZONE 'UTC',
    ALTER COLUMN update_time TYPE timestamp(6) with time zone USING update_time AT TIME ZONE 'UTC';

-- Optimistic locking version column; existing rows start at 0.
ALTER TABLE orders
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
