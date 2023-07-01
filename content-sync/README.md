# Content sync
Fetch various content (such as news, videos) and periodically get them through Telegram Bot.

## Supported integrations
- [News API](https://newsapi.org/docs/get-started)
- [Youtube Data API V3](https://developers.google.com/youtube/v3/docs)

## Architecture diagram
![Diagram](media/ContentSync.jpg)

## Stack
- **ZIO**
  - ZIO Temporal (Protobuf transport)
  - ZIO Streams,
  - ZIO Logging
  - ZIO Config
  - ZIO JSON
  - ZIO HTTP
- **Data processing**
  - Apache Spark
- **Database layer**
  - ZIO Quill
  - Flyway
- **Integrations**
  - STTP
  - Official Google API Client
  - Telegramium
- **Testing**
  - ZIO Temporal Testkit 
  - ZIO Test
  - Mockito

## How to read the code
- [Shared](./shared) contains code shared among all components
  - [Shared protobuf](./shared/src/main/protobuf)
  - [Flyway migrations](./shared/src/main/resources/db/migration)
- [Content puller](./content-puller) pulls data from integrations
  - [Protobuf](./content-puller/src/main/protobuf)
  - [Tests](./content-puller/src/test)
- [Content processor](./content-processor) processes pulled data & creates recommendation feed
  - [Protobuf](./content-processor/src/main/protobuf)
- [Telegram bot](./telegram-bot) - the bot implementation
  - [Protobuf](./telegram-bot/src/main/protobuf)

## Run examples
**(1)** Create a `secret.env` file in the project root. It must contain the following secrets:
- Telegram (taken from BotFather):
  - **TELEGRAM_BOT_TOKEN**
  - **TELEGRAM_BOT_USERNAME**
- Youtube (taken from google console):
  - **OAUTH2_CLIENT_CLIENT_ID**
  - **OAUTH2_CLIENT_CLIENT_SECRET**

**Integration Notes:**
- Telegram Bot [FAQ](https://core.telegram.org/bots/faq)
- Youtube API [Getting started guide](https://developers.google.com/youtube/v3/getting-started)

**(2)** Start temporal cluster  
(either on your own or in Docker from the [parent directory](../docker-compose.yaml))


**(3a)** Run each component locally:
```shell
# Initialize database
make start-local-env
# content sync components
make start-puller-local
make start-processor-local
make start-telegram-bot-local
```

**(3b)** Or assemble docker images & run them:
```shell
# build docker images
sbt docker:publishLocal

# start dockerized env
make start-dockerized-env
```

**(4)** Interact with the Telegram bot!  
![Bot](media/StartTgBot.png)