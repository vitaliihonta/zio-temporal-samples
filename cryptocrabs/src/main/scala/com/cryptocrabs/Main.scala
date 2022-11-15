package com.cryptocrabs

import com.cryptocrabs.clients.ExchangeClientService
import zio.*
import zio.logging.backend.SLF4J
import zio.temporal.activity.ZActivityOptions
import zio.temporal.worker.ZWorkerFactory
import zio.temporal.workflow.ZWorkflowClient
import zio.temporal.workflow.ZWorkflowServiceStubs

object Main extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val exampleFlow = ZIO.serviceWithZIO[ExchangeClientService] { exchangeClient =>
      for {
        _        <- ZIO.logInfo(s"Going to create an exchange order")
        sender   <- Random.nextUUID
        receiver <- Random.nextUUID
        amount = BigDecimal(42.00)
        _ <- exchangeClient.exchangeOrder(sender, receiver, amount)
        _ <- ZIO.logInfo("Crypto exchange successful!")
      } yield ()
    }

    // Setting up the worker
    val program = ZIO.serviceWithZIO[ZWorkerFactory] { workerFactory =>
      workerFactory.use {
        for {
          stubs <- ZIO.service[ZWorkflowServiceStubs]
          _ <- stubs.use() {
                 exampleFlow
               }
        } yield ()
      }
    }

    program.provide(
      ExchangeClientService.make,
      ZWorkflowClient.make,
      ZActivityOptions.default,
      ZWorkflowServiceStubs.make,
      ZWorkerFactory.make,
      TemporalModule.workerFactoryOptions,
      TemporalModule.stubOptions,
      TemporalModule.clientOptions,
      TemporalModule.worker,
      SLF4J.slf4j(LogLevel.Debug)
    )
  }
}
