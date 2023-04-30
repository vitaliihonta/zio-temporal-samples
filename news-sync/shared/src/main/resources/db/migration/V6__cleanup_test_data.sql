DELETE FROM  news_feed_recommendation_article
 WHERE recommendation IN (
    SELECT id FROM news_feed_recommendation WHERE owner = 'a70ab761-3188-4896-93a0-a1b558f915fe'
 );

DELETE FROM  news_feed_recommendation WHERE owner = 'a70ab761-3188-4896-93a0-a1b558f915fe';

DELETE FROM  news_feed_article
WHERE topic IN (
  SELECT id FROM news_feed_topic WHERE owner = 'a70ab761-3188-4896-93a0-a1b558f915fe'
);

DELETE FROM news_feed_integration WHERE reader = 'a70ab761-3188-4896-93a0-a1b558f915fe';
DELETE FROM news_feed_topic WHERE owner = 'a70ab761-3188-4896-93a0-a1b558f915fe';
DELETE FROM reader WHERE id = 'a70ab761-3188-4896-93a0-a1b558f915fe';
