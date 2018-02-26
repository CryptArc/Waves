package com.wavesplatform.matcher.market

import akka.actor.{Actor, ActorRef, Cancellable, Props, ReceiveTimeout}
import com.wavesplatform.matcher.market.OrderBookActor.ForceCancelOrder
import com.wavesplatform.matcher.market.OrderHistoryActor.{ForceCancelOrderFromHistory, GetActiveOrdersByAddress, GetActiveOrdersByAddressResponse}
import com.wavesplatform.matcher.model.Events.BalanceChanged
import com.wavesplatform.matcher.model.LimitOrder
import com.wavesplatform.state2.Portfolio
import scorex.transaction.assets.exchange.AssetPair
import scorex.utils.ScorexLogging
import BalanceWatcherActor._

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class BalanceWatcherActor(matcher: ActorRef, orderHistory: ActorRef) extends Actor with ScorexLogging {

  private type OrdersToDelete   = List[(AssetPair, String)]
  private type ChangesByAddress = Map[String, Portfolio]

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[BalanceChanged])
  }

  override def receive: Receive = {
    case x @ BalanceChanged(changes) =>
      log.debug(s"Received in receive: $x")
      becomeWorking(changes)
  }

  private def waitOrders(stashedChanges: ChangesByAddress, changesByAddress: ChangesByAddress, waitOrdersTimeout: Cancellable): Receive = {
    case x @ GetActiveOrdersByAddressResponse(address, orders) =>
      log.debug(s"Received in waitOrders: $x")
      // just check portfolio without any arithmetic operations
      ordersToDelete(changesByAddress, address, orders) // ERROR
      //        .map(Function.tupled(ForceCancelOrder))
      //          .map { x =>
      //            log.debug(s"Sending to matcher: $x")
      //            x
      //          }
      // .foreach(matcher ! _)
        .foreach {
          case (pair, id) =>
            log.debug(s"Sending to history: $id")
            orderHistory ! ForceCancelOrderFromHistory(id)

            log.debug(s"Sending to matcher: $id")
            matcher ! ForceCancelOrder(pair, id)
        }

      val updated = changesByAddress - address
      if (updated.isEmpty) {
        if (stashedChanges.isEmpty) context.become(receive)
        else becomeWorking(stashedChanges)
      } else context.become(waitOrders(stashedChanges, updated, reschedule(waitOrdersTimeout)))

    case x: BalanceChanged =>
      log.debug(s"Received in waitOrders: $x")
      context.become(
        waitOrders(
          stashedChanges = replaceChanges(stashedChanges, x.changesByAddress),
          changesByAddress = changesByAddress,
          waitOrdersTimeout = reschedule(waitOrdersTimeout)
        ))

    case _: ReceiveTimeout =>
      log.warn(s"Timeout to process orders for ${changesByAddress.size} addresses has been reached")
      if (stashedChanges.isEmpty) context.become(receive)
      else becomeWorking(stashedChanges)
  }

  private def becomeWorking(changes: ChangesByAddress): Unit = {
    changes.keys
      .map(GetActiveOrdersByAddress)
      .map { x =>
        log.debug(s"Sending to orderHistory: $x"); x
      }
      .foreach(orderHistory ! _)
    context.become(waitOrders(Map.empty, changes, reschedule(EmptyCancellable)))
  }

  private def reschedule(old: Cancellable): Cancellable = {
    import context.dispatcher

    old.cancel()
    context.system.scheduler.scheduleOnce(TimeoutToProcessChanges, self, ReceiveTimeout)
  }

  private def replaceChanges(orig: ChangesByAddress, replacement: ChangesByAddress): ChangesByAddress = orig ++ replacement

  private def ordersToDelete(portfolios: Map[String, Portfolio], ownerAddress: String, orders: Seq[LimitOrder]): OrdersToDelete = {
    portfolios
      .get(ownerAddress)
      .map { portfolio =>
        val ordersByPriority = orders.sortBy(_.order.timestamp)(Ordering[Long].reverse)
        val (_, r) = ordersByPriority.foldLeft((portfolio, List.empty: OrdersToDelete)) {
          case ((restPortfolio, toDelete), limitOrder) =>
            val updatedPortfolio1 = restPortfolio.copy(balance = restPortfolio.balance - restPortfolio.leaseInfo.leaseOut)
            val updatedPortfolio2 = updatedPortfolio1.remove(limitOrder.spentAcc.assetId, limitOrder.getSpendAmount)
            val updatedPortfolio3 = updatedPortfolio2.flatMap(_.remove(None, limitOrder.remainingFee))

            log.debug(s"$restPortfolio -> $updatedPortfolio1 -> $updatedPortfolio2 -> $updatedPortfolio3")

            updatedPortfolio3 match {
              case Some(x) => (x, toDelete)
              case None =>
                (restPortfolio, (limitOrder.order.assetPair -> limitOrder.order.idStr()) :: toDelete)
            }
        }
        r
      }
      .getOrElse(List.empty)
  }

}

object BalanceWatcherActor {
  val TimeoutToProcessChanges: FiniteDuration = 1.minute

  val EmptyCancellable: Cancellable = new Cancellable {
    override def cancel(): Boolean    = true
    override def isCancelled: Boolean = true
  }

  def props(matcher: ActorRef, orderHistory: ActorRef): Props = Props(new BalanceWatcherActor(matcher, orderHistory))
}
