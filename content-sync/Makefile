JAVA_OPTS="-Duser.timezone=UTC"
TELEGRAM_BINDING_PORT="9092"

include secret.env
export $(shell sed 's/=.*//' secret.env)

.PHONY: help
# target: help - Display callable targets
help:
	@egrep "^# target:" [Mm]akefile

.PHONY: dockerize
dockerize:
	sbt docker:publishLocal

.PHONY: start-dockerized-env
start-dockerized-env:
	docker-compose -f docker-compose-env.yaml up -d

.PHONY: stop-dockerized-env
stop-dockerized-env:
	docker-compose -f docker-compose-env.yaml down

.PHONY: start-local-env
start-local-env:
	docker-compose -f docker-compose-local.yaml up -d

.PHONY: stop-local-env
stop-local-env:
	docker-compose -f docker-compose-local.yaml down

.PHONY: start-puller-local
start-puller-local:
	JAVA_OPTS=$(JAVA_OPTS) sbt "content-puller/run $(reset)"

.PHONY: start-processor-local
start-processor-local:
	JAVA_OPTS=$(JAVA_OPTS) sbt "content-processor/run $(reset)"

.PHONY: start-telegram-bot-local
start-telegram-bot-local:
	TELEGRAM_BINDING_PORT=$(TELEGRAM_BINDING_PORT) JAVA_OPTS=$(JAVA_OPTS) sbt "telegram-bot/run $(reset)"

.PHONY: psql
psql:
	docker exec -it content-sync-db-1 psql -d content-sync -U postgres