-- Doesn't matter in an example project
DELETE FROM content_feed_recommendation;
DELETE FROM content_feed_item;
-- Make integration required
ALTER TABLE content_feed_item ADD COLUMN integration BIGINT NOT NULL REFERENCES content_feed_integration(id);

ALTER TABLE content_feed_recommendation DROP COLUMN topic;
ALTER TABLE content_feed_recommendation ADD COLUMN integration BIGINT NOT NULL REFERENCES content_feed_integration(id);