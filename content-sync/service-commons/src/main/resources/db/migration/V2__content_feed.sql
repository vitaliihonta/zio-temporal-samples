CREATE TABLE IF NOT EXISTS content_feed_topic(
    id UUID NOT NULL PRIMARY KEY,
    owner UUID NOT NULL REFERENCES subscriber(id),
    topic varchar(256) NOT NULL,
    lang varchar(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS content_feed_recommendation(
    id UUID NOT NULL PRIMARY KEY,
    owner UUID NOT NULL REFERENCES subscriber(id),
    integration BIGINT NOT NULL REFERENCES content_feed_integration(id),
    for_date DATE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS content_feed_recommendation_for_date
  ON content_feed_recommendation(owner, for_date, integration);

CREATE TABLE IF NOT EXISTS content_feed_recommendation_item(
    recommendation UUID NOT NULL REFERENCES content_feed_recommendation(id),
    topic UUID NULL REFERENCES content_feed_topic(id),
    title varchar(4196) NOT NULL,
    description varchar(8392) NOT NULL,
    url varchar(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL
);

CREATE INDEX IF NOT EXISTS content_feed_recommendation_item_id ON content_feed_recommendation_item(recommendation);
