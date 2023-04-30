.PHONY: help
# target: help - Display callable targets
help:
	@egrep "^# target:" [Mm]akefile

.PHONY: start-env
start-env:
	docker-copmose up -d

.PHONY: stop-env
stop-env:
	docker-copmose down

.PHONY: start-puller-local
start-puller-local:
	sbt "news-puller/run $(reset)"

.PHONY: start-processor-local
start-processor-local:
	sbt "news-processor/run $(reset)"

.PHONY: start-telegram-bot-local
start-telegram-bot-local:
	sbt "telegram-push/run $(reset)"

.PHONY: psql
psql:
	docker exec -it news-sync-db-1 psql -d news -U postgres