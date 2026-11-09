-- Audit-friendly failure observations. Does NOT represent DLQ delivery or offset ACK.
-- Never persist raw Kafka payloads, keys, exception messages or credentials.
CREATE TABLE kafka_consumer_failure_observations (
    consumer_name VARCHAR(128) NOT NULL CHECK (length(trim(consumer_name)) > 0),
    topic VARCHAR(249) NOT NULL CHECK (length(trim(topic)) > 0),
    partition_number INTEGER NOT NULL CHECK (partition_number >= 0),
    record_offset BIGINT NOT NULL CHECK (record_offset >= 0),
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    failure_category VARCHAR(32) NOT NULL CHECK (
        failure_category IN ('INVALID_CONTRACT', 'UNTRUSTED_EVENT', 'PROCESSING_FAILURE')
    ),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    occurrence_count INTEGER NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    PRIMARY KEY (consumer_name, topic, partition_number, record_offset)
);
CREATE INDEX idx_kafka_failure_first_seen ON kafka_consumer_failure_observations (first_seen_at);
