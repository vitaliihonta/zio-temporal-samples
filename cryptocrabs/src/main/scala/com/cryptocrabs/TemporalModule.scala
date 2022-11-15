package com.cryptocrabs

import com.cryptocrabs.workflows.{ExchangeWorkflow, ExchangeWorkflowImpl}
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

  val worker: URLayer[ZWorkerFactory, Unit] =
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[ZWorkerFactory] { workerFactory =>
        for {
          worker <- workerFactory.newWorker(TaskQueues.exchanger)
//          _ = worker.addActivityImplementation(activityImpl)
          _ = worker.addWorkflow[ExchangeWorkflow].from(new ExchangeWorkflowImpl)
        } yield ()
      }
    }
}
