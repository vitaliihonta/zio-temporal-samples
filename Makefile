.PHONY: help
# target: help - Display callable targets
help:
	@egrep "^# target:" [Mm]akefile


.PHONY: clean
clean:
	sbt clean


.PHONY: start-temporal
start-temporal:
	temporal server start-dev --dynamic-config-value 'frontend.workerVersioningDataAPIs=true' --dynamic-config-value 'frontend.workerVersioningWorkflowAPIs=true'
