-- users settings
CREATE TABLE reader_settings(
    reader UUID NOT NULL REFERENCES reader(id),
    modified_at TIMESTAMP NOT NULL,
    timezone varchar(120) NULL,
    PRIMARY KEY(reader)
);

INSERT INTO reader_settings (reader, modified_at, timezone)
SELECT id, CURRENT_TIMESTAMP, 'UTC' FROM reader;

ALTER TABLE reader_settings ALTER COLUMN timezone SET NOT NULL;