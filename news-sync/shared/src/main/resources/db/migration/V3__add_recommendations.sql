CREATE TABLE news_feed_recommendation(
    id UUID NOT NULL PRIMARY KEY,
    owner UUID NOT NULL REFERENCES reader(id),
    topic UUID NOT NULL REFERENCES news_feed_topic(id),
    for_date DATE NOT NULL
);

CREATE UNIQUE INDEX news_feed_recommendation_for_date ON news_feed_recommendation(topic, for_date);

CREATE TABLE news_feed_recommendation_article(
    recommendation UUID NOT NULL REFERENCES news_feed_recommendation(id),
    article UUID NOT NULL REFERENCES news_feed_article(id),
    PRIMARY KEY (recommendation, article)
);
