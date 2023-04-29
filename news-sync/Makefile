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