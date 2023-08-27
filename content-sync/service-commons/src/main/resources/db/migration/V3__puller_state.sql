CREATE TABLE IF NOT EXISTS puller_state(
    integration BIGINT NOT NULL PRIMARY KEY REFERENCES content_feed_integration(id),
    value JSONB NOT NULL
);
