CREATE TABLE content_feed_integration(
    id BIGSERIAL NOT NULL PRIMARY KEY,
    subscriber UUID NOT NULL REFERENCES subscriber(id),
    integration JSONB NOT NULL
);

CREATE INDEX content_feed_integration_type_idx ON content_feed_integration((integration->>'type'));
