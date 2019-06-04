package org.flocka.Services.Order

import java.net.URI
import akka.http.scaladsl.model.IllegalResponseException
import akka.http.scaladsl.server.Directives.{complete, onComplete}
import org.flocka.ServiceBasics.{CommandHandler, IdGenerator, MessageTypes, QueryHandler}
import org.flocka.Services.Order.OrderService.timeoutTime
import org.flocka.Services.Order.OrderServiceComs.{FindOrder, OrderFound}
import org.flocka.sagas.{Saga, SagaOperation}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object CheckoutSaga extends QueryHandler{
  def createCheckoutSaga(orderId: Long, idGenerator: IdGenerator, loadBalancerURI: String, queryHandler: MessageTypes.Query => Future[Any])
                        (implicit executor: ExecutionContext): Future[Saga] = {
    /**
      * get order details
      */
    val orderDetailsFuture: Future[OrderFound] = findOrderDetails(orderId, queryHandler)
    orderDetailsFuture.map{ orderDetails =>
      /**
        * generate a unique id for the saga
        */
      val id: Long = idGenerator.generateId(100)
      val orderSaga: Saga = new Saga(id)

      /**
        * decrease stock for all items
        */
      for((itemId, _) <- orderDetails.items){
        orderSaga.addConcurrentOperation(createDecreaseStockOperation(itemId, 1, loadBalancerURI))
      }
      /**
        * make the user pay for this order
        */
      orderSaga.addConcurrentOperation(createPayOrderOperation(orderId, orderDetails.userId, loadBalancerURI))
      return Future(orderSaga)
    }
  }

  def findOrderDetails(orderId: Long, queryHandler: MessageTypes.Query => Future[Any])
                      (implicit executor: ExecutionContext): Future[OrderFound] = {
    queryHandler(FindOrder(orderId)).map { result: Any =>
      result match {
        case orderDetails@OrderFound(_, _, _, _) => return Future(orderDetails)
        case _ => throw new IllegalArgumentException(result.toString + " is not a valid response for FindOrder query.")
      }
    }
  }

  def createPayOrderOperation(orderId: Long, userId: Long, loadBalancerURI: String): SagaOperation ={
    return new SagaOperation(
      URI.create(loadBalancerURI + "/pay/" + userId + "/" + orderId),
      URI.create(loadBalancerURI + "/cancelPayment/" + userId + "/" + orderId),
      paymentPostCondition(orderId))
  }

  def createDecreaseStockOperation(itemId: Long, amount: Long, loadBalancerURI: String): SagaOperation = {
    return new SagaOperation(
      URI.create(loadBalancerURI + "/stock/add/" + itemId + "/" + amount),
      URI.create(loadBalancerURI + "/stock/subtract/" + itemId + "/" + amount),
      decreaseStockPostCondition(itemId, amount))
  }

  /**
    * Post condition for the payment operation.
    * @param orderId
    * @return
    */
  def paymentPostCondition(orderId: Long): String => Boolean ={ result ⇒
    //ToDo: actually check for events not for strings
    result.contains("PaymentPayed") && result.contains("true") && result.contains(orderId)
  }

  /**
    * Post condition for the decrease stock operation.
    * @param itemId
    * @return
    */
  def decreaseStockPostCondition(itemId: Long, amount: Long): String => Boolean ={ result ⇒
    //ToDo: actually check for events not for strings
    result.contains("DecreaseAvailability") && result.contains(itemId) && result.contains(amount)
  }
}
