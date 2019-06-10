package org.flocka.Services.Payment

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.stream.StreamTcpException
import org.flocka.ServiceBasics.PersistentActorBase.InvalidOperationException
import org.flocka.Services.Payment.PaymentServiceComs.PaymentPayed

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class ExecutePayment(requester: ActorRef, userId: Long, orderId: Long, operationId: Long, loadBalancerURIOrder: String, loadBalancerURIUser: String)

class PaymentExecutor extends Actor {


  implicit val executionContext:  ExecutionContext = context.dispatcher
  implicit val system:  ActorSystem = context.system



  override def receive: Receive = {

    case ExecutePayment(requester, userId, orderId, operationId, loadBalancerURIOrder, loadBalancerURIUser) =>
      var resultEvent: PaymentPayed = new PaymentPayed(-1, -1, false, -1)
      val orderUri = loadBalancerURIOrder + "/orders/find/" + orderId

      val orderDetailsFuture = Http(system).singleRequest(HttpRequest(method = HttpMethods.GET, uri = orderUri) )
      orderDetailsFuture.onComplete {
        case Success(value) =>
          val amount = getTotalOrderCost(value.entity.toString)
          val decreaseCreditUri = loadBalancerURIUser + "/users/credit/subtract/" + userId + "/" + amount
          val decCredFuture = Http(system).singleRequest(HttpRequest(method = HttpMethods.POST, uri = decreaseCreditUri))
          decCredFuture.onComplete {
            case Success(value2) =>
              val creditSubtracted: Boolean  = value2.entity.toString.contains("CreditSubtracted") && value2.entity.toString.contains("true")
              sender() ! PaymentPayed(userId, orderId, creditSubtracted, operationId)
              context.stop(self)
            case Failure(exception) =>
              sender() ! PaymentPayed(userId, orderId, false, operationId)
              context.stop(self)

          }
        case Failure(exception) =>
          sender() ! PaymentPayed(userId, orderId, false, operationId)
          context.stop(self)
      }


  }

  private def getTotalOrderCost(response: String): Long = {
    var amount: Long = 0
    var currTuple = ""
    if (response.contains("List((")) { //has more than one item
      val listOfTuples = response.split("List\\(")(1).split("\\), \\(")
      for (tuple <- listOfTuples) {
        currTuple = tuple replaceAll("\\(", "")
        currTuple = tuple replaceAll("\\)", "")
        amount += currTuple.split(",")(1).toLong
      }
    }
    return amount
  }
}
