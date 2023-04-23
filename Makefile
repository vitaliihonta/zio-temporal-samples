.PHONY: help
# target: help - Display callable targets
help:
	@egrep "^# target:" [Mm]akefile


.PHONY: clean
clean:
	sbt clean


.PHONY: start-temporal
start-temporal:
	docker-compose up -d

.PHONY: stop-temporal
stop-temporal:
	docker-compose down
