package com.cryptostock

import com.cryptostock.clients.ExchangeClientService
import com.cryptostock.workflows.ExchangeOrderActivityImpl
import zio.*
import zio.logging.backend.SLF4J
import zio.temporal.activity.{ZActivityOptions, ZActivityRunOptions}
import zio.temporal.worker.ZWorkerFactory
import zio.temporal.workflow.{
  ZWorkflowClient,
  ZWorkflowClientOptions,
  ZWorkflowServiceStubs,
  ZWorkflowServiceStubsOptions
}
import zio.temporal.worker.ZWorkerFactoryOptions

import java.util.UUID

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override val run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val exampleFlow = ZIO.serviceWithZIO[ExchangeClientService] { exchangeClient =>
      val pause = ZIO.sleep(2.seconds)
      for {
        _      <- ZIO.logInfo(s"Going to create an exchange order")
        seller <- Random.nextUUID
        amount   = BigDecimal(42.00)
        currency = CryptoCurrency.CrabsCoin
        orderId <- exchangeClient.exchangeOrder(seller, amount, currency)
        _       <- ZIO.logInfo("Order placed")
        _ <- {
          val pollStatus = exchangeClient
            .getStatus(orderId)
            .tap(status => ZIO.logInfo(s"--- Current order status $status"))

          pollStatus
            .repeat(
              Schedule.spaced(5.seconds).unit &&
                Schedule.recurUntil[ExchangeOrderStatus](_.isFinal).unit
            )
            .fork
        }
        // Those steps are optional! Comment/uncomment them (in correct order)
        _ <- pause *> Random.nextUUID.flatMap(exchangeClient.acceptOrder(orderId, _))
        _ <- pause *> exchangeClient.buyerTransferConfirmation(orderId, "https://example.com")
        _ <- pause *> exchangeClient.sellerTransferConfirmation(orderId)
        // End optional steps
        result <- exchangeClient.waitForResult(orderId)
        _      <- ZIO.logInfo(s"Exchange finished result=$result")
      } yield ()
    }

    // Setting up the worker
    val program = for {
      _ <- ZWorkflowServiceStubs.setup()
      _ <- ZWorkerFactory.setup
      _ <- exampleFlow
    } yield ()

    program.provideSome[Scope](
      // client
      ExchangeClientService.make,
      ZWorkflowClient.make,
      // worker
      ExchangeOrderActivityImpl.make,
      ZWorkerFactoryOptions.make,
      ZWorkflowServiceStubsOptions.make,
      TemporalModule.clientOptions,
      TemporalModule.worker,
      ZActivityRunOptions.default,
      ZWorkflowServiceStubs.make,
      ZWorkerFactory.make
    )
  }
}
