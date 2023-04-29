CREATE TABLE news_feed_integration(
    reader UUID NOT NULL REFERENCES reader(id),
    integration JSONB NOT NULL
);

CREATE INDEX news_feed_integration_type_idx ON news_feed_integration((integration->>'type'));
