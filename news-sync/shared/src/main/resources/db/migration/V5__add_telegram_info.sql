ALTER TABLE reader ADD COLUMN telegram_id BIGINT NULL;
ALTER TABLE reader ADD COLUMN telegram_chat_id BIGINT NULL;

UPDATE reader SET telegram_id = -1 WHERE telegram_id IS NULL;
UPDATE reader SET telegram_chat_id = -1 WHERE telegram_chat_id IS NULL;

ALTER TABLE reader ALTER COLUMN telegram_id SET NOT NULL;
ALTER TABLE reader ALTER COLUMN telegram_chat_id SET NOT NULL;

CREATE UNIQUE INDEX reader_telegram_id ON reader(telegram_id);
