INSERT INTO reader(id, registered_at)
VALUES ('a70ab761-3188-4896-93a0-a1b558f915fe'::UUID, CURRENT_TIMESTAMP);

INSERT INTO news_feed_topic(id, owner, topic, lang)
VALUES (GEN_RANDOM_UUID(), 'a70ab761-3188-4896-93a0-a1b558f915fe', 'UEFA Champions League', 'english');