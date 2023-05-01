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