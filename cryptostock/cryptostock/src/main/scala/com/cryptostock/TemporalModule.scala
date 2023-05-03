package com.cryptostock

import com.cryptostock.workflows.{ExchangeOrderActivity, ExchangeWorkflow, ExchangeWorkflowImpl}
import zio.*
import zio.temporal.*
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker.{ZWorker, ZWorkerFactory, ZWorkerFactoryOptions}
import zio.temporal.workflow.{ZWorkflowClientOptions, ZWorkflowServiceStubsOptions}

object TemporalModule {

  val clientOptions: Layer[Config.Error, ZWorkflowClientOptions] =
    ZWorkflowClientOptions.make @@
      ZWorkflowClientOptions.withDataConverter(ProtobufDataConverter.makeAutoLoad())

  val worker: URLayer[ZWorkerFactory with ExchangeOrderActivity, Unit] =
    ZLayer.fromZIO {
      ZWorkerFactory.newWorker(TaskQueues.exchanger) @@
        ZWorker.addActivityImplementationService[ExchangeOrderActivity] @@
        ZWorker.addWorkflow[ExchangeWorkflowImpl].fromClass
    }.unit
}
