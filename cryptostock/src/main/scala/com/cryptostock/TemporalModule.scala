package com.cryptostock

import com.cryptostock.workflows.{ExchangeOrderActivity, ExchangeWorkflow, ExchangeWorkflowImpl}
import zio.*
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker.ZWorker
import zio.temporal.worker.ZWorkerFactory
import zio.temporal.worker.ZWorkerFactoryOptions
import zio.temporal.workflow.ZWorkflowClientOptions
import zio.temporal.workflow.ZWorkflowServiceStubsOptions

object TemporalModule {
  val stubOptions: ULayer[ZWorkflowServiceStubsOptions] = ZLayer.succeed {
    ZWorkflowServiceStubsOptions.default
  }

  val clientOptions: ULayer[ZWorkflowClientOptions] = ZLayer.succeed {
    ZWorkflowClientOptions.default.withDataConverter(
      ProtobufDataConverter.makeAutoLoad()
    )
  }

  val workerFactoryOptions: ULayer[ZWorkerFactoryOptions] = ZLayer.succeed {
    ZWorkerFactoryOptions.default
  }

  val worker: URLayer[ZWorkerFactory with ExchangeOrderActivity, Unit] =
    ZLayer.fromZIO {
      ZWorkerFactory.newWorker(TaskQueues.exchanger) @@
        ZWorker.addActivityImplementationService[ExchangeOrderActivity] @@
        ZWorker.addWorkflow[ExchangeWorkflowImpl].fromClass
    }.unit
}
