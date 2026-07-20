-- Idempotency + offset tracking for CQRS event projections.

CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE projection_offset (
    consumer_group  VARCHAR(100) NOT NULL,
    topic           VARCHAR(200) NOT NULL,
    partition_id    INTEGER      NOT NULL,
    committed_offset BIGINT      NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, topic, partition_id)
);
