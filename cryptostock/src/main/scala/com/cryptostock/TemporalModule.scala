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
      ZIO.serviceWithZIO[ZWorkerFactory] { workerFactory =>
        for {
          worker           <- workerFactory.newWorker(TaskQueues.exchanger)
          exchangeActivity <- ZIO.service[ExchangeOrderActivity]
          _ = worker.addActivityImplementation(exchangeActivity)
          _ = worker.addWorkflow[ExchangeWorkflow].from(new ExchangeWorkflowImpl)
        } yield ()
      }
    }
}
