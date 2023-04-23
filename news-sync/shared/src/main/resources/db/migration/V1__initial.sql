-- users
CREATE TABLE reader(
    id UUID NOT NULL PRIMARY KEY,
    registered_at TIMESTAMP NOT NULL
);

-- News feed
CREATE TABLE news_feed_topic(
    id UUID NOT NULL PRIMARY KEY,
    owner UUID NOT NULL REFERENCES reader(id),
    topic varchar(256) NOT NULL,
    lang varchar(128) NOT NULL
);

CREATE TABLE news_feed_article(
    id UUID NOT NULL PRIMARY KEY,
    topic UUID NOT NULL REFERENCES news_feed_topic(id),
    title varchar(4196) NOT NULL,
    description varchar(8392) NOT NULL,
    url varchar(512) NOT NULL,
    published_at timestamp NOT NULL
);