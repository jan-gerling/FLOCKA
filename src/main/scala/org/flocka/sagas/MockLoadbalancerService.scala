package org.flocka.Services.User

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics.{CommandHandler, MessageTypes, QueryHandler}
import org.flocka.Services.User.UserServiceComs._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


/**
  * Contains routes for the Rest User Service. Method bind is used to start the server.
  */
object MockLoadbalancerService {

  val randomGenerator: scala.util.Random = scala.util.Random
  val service = "lb"
  val timeoutTime: FiniteDuration = 500 millisecond
  implicit val timeout: Timeout = Timeout(timeoutTime)
  var paymentsDone = 0
  var decreaseStocksDone = 0

  val happy = false

  /**
    * Starts the server
    *
    * @param exposedPort the port in which to expose the service
    * @param executor    jeez idk,
    * @param system      the ActorSystem
    * @return
    */
  def bind(exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding] = {


    val postPayPaymentRoute: Route = {
      pathPrefix(service / "pay" / LongNumber / LongNumber / LongNumber.?) { (userId, orderId, operationId) ⇒
          post {
            pathEndOrSingleSlash {
              println("A")

              if (happy) {
                complete("User  " + userId + " pays for " + orderId)
              }
              else {
                if (paymentsDone % 3 != 2) {
                  paymentsDone += 1

                  complete("User  " + userId + " pays for " + orderId)
                }else {
                  paymentsDone += 1

                  complete("Failed")
                }
              }
            }
        }
      }
    }

    val postCancelPaymentRoute: Route = {
      pathPrefix(service / "cancelPayment" / LongNumber / LongNumber / LongNumber.?) { (userId, orderId, operationId) ⇒
          println("B")
          post {
            pathEndOrSingleSlash {
              complete("User  " + userId + " cancels payment for " + orderId)
            }
          }
      }
    }

    val postDecreaseItemAvailabilityRoute: Route = {
      pathPrefix(service / "subtract" / LongNumber / LongNumber / LongNumber.?) { (itemId, amount, operationId) ⇒
          post {
            pathEndOrSingleSlash {
              //Thread.sleep(6000)
              println("C")
              if (happy) {
                complete("Stock decreased by " + amount)
              }else {
                if(decreaseStocksDone % 3 != 2) {
                  decreaseStocksDone += 1

                  complete("Stock decreased by " + amount)
                }else{
                  decreaseStocksDone += 1

                  complete("Failed")
                }
              }
          }
        }
      }
    }

    val postIncreaseItemAvailabilityRoute: Route = {
      pathPrefix(service / "add" / LongNumber / LongNumber / LongNumber.?) { (itemId, amount, operationId) ⇒
          post {
            pathEndOrSingleSlash {
              println("D")
              complete("Stock increased by " + amount)
            }
        }
      }
    }


    def route: Route = postCancelPaymentRoute ~ postDecreaseItemAvailabilityRoute ~ postIncreaseItemAvailabilityRoute ~ postPayPaymentRoute

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}