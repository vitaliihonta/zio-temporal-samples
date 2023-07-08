-- users
CREATE TABLE IF NOT EXISTS subscriber(
    id UUID NOT NULL PRIMARY KEY,
    registered_at TIMESTAMP NOT NULL,
    telegram_id BIGINT NOT NULL UNIQUE,
    telegram_chat_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS subscriber_settings(
    subscriber UUID NOT NULL REFERENCES subscriber(id),
    modified_at TIMESTAMP NOT NULL,
    timezone varchar(120) NOT NULL DEFAULT 'UTC',
    publish_at TIME NOT NULL DEFAULT TIME '19:00',
    PRIMARY KEY(subscriber)
);

CREATE TABLE IF NOT EXISTS content_feed_integration(
    id BIGSERIAL NOT NULL PRIMARY KEY,
    subscriber UUID NOT NULL REFERENCES subscriber(id),
    integration JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS content_feed_integration_type_idx ON content_feed_integration((integration->>'type'));
