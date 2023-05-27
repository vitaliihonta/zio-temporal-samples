CREATE TABLE content_feed_recommendation(
    id UUID NOT NULL PRIMARY KEY,
    owner UUID NOT NULL REFERENCES subscriber(id),
    topic UUID NOT NULL REFERENCES content_feed_recommendation(id),
    for_date DATE NOT NULL
);

CREATE UNIQUE INDEX content_feed_recommendation_for_date ON content_feed_recommendation(topic, for_date);

CREATE TABLE content_feed_recommendation_item(
    recommendation UUID NOT NULL REFERENCES content_feed_recommendation(id),
    item UUID NOT NULL REFERENCES content_feed_item(id),
    PRIMARY KEY (recommendation, item)
);
