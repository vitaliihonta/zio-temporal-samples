-- users
CREATE TABLE subscriber(
    id UUID NOT NULL PRIMARY KEY,
    registered_at TIMESTAMP NOT NULL,
    telegram_id BIGINT NOT NULL UNIQUE,
    telegram_chat_id BIGINT NOT NULL
);

CREATE TABLE subscriber_settings(
    subscriber UUID NOT NULL REFERENCES subscriber(id),
    modified_at TIMESTAMP NOT NULL,
    timezone varchar(120) NOT NULL DEFAULT 'UTC',
    publish_at TIME NOT NULL DEFAULT TIME '19:00',
    PRIMARY KEY(subscriber)
);

-- Content feed
CREATE TABLE content_feed_topic(
    id UUID NOT NULL PRIMARY KEY,
    owner UUID NOT NULL REFERENCES subscriber(id),
    topic varchar(256) NOT NULL,
    lang varchar(128) NOT NULL
);

CREATE TABLE content_feed_item(
    id UUID NOT NULL PRIMARY KEY,
    topic UUID NOT NULL REFERENCES content_feed_topic(id),
    title varchar(4196) NOT NULL,
    description varchar(8392) NULL,
    url varchar(512) NOT NULL,
    published_at timestamp NOT NULL
);