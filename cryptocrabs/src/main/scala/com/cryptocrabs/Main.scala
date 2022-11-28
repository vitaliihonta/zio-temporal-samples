package com.cryptocrabs

import com.cryptocrabs.clients.ExchangeClientService
import com.cryptocrabs.workflows.ExchangeOrderActivityImpl
import zio.*
import zio.logging.backend.SLF4J
import zio.temporal.activity.ZActivityOptions
import zio.temporal.worker.ZWorkerFactory
import zio.temporal.workflow.ZWorkflowClient
import zio.temporal.workflow.ZWorkflowServiceStubs

import java.util.UUID

object Main extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val exampleFlow = ZIO.serviceWithZIO[ExchangeClientService] { exchangeClient =>
      val pause = ZIO.sleep(2.seconds)
      for {
        _      <- ZIO.logInfo(s"Going to create an exchange order")
        seller <- Random.nextUUID
        amount   = BigDecimal(42.00)
        currency = CryptoCurrency.CrabsCoin
        orderId <- exchangeClient.exchangeOrder(seller, amount, currency)
        _       <- ZIO.logInfo("Order placed")
        poll <- {
          val pollStatus = exchangeClient
            .getStatus(orderId)
            .flatMap(order => ZIO.logInfo(s"--- Current order status $order"))

          pollStatus.repeat(Schedule.spaced(5.seconds).unit).forkDaemon
        }
        // Those steps are optional! Comment/uncomment them
//        _ <- pause *> Random.nextUUID.flatMap(exchangeClient.acceptOrder(orderId, _))
//        _ <- pause *> exchangeClient.buyerTransferConfirmation(orderId, "https://example.com")
//        _ <- pause *> exchangeClient.sellerTransferConfirmation(orderId)
        // End optional steps
        result <- exchangeClient.waitForResult(orderId)
        _      <- poll.interrupt
        _      <- ZIO.logInfo(s"Exchange finished result=$result")
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
      // client
      ExchangeClientService.make,
      ZWorkflowClient.make,
      // worker
      ExchangeOrderActivityImpl.make,
      TemporalModule.workerFactoryOptions,
      TemporalModule.stubOptions,
      TemporalModule.clientOptions,
      TemporalModule.worker,
      ZActivityOptions.default,
      ZWorkflowServiceStubs.make,
      ZWorkerFactory.make,
      SLF4J.slf4j(LogLevel.Debug)
    )
  }
}
