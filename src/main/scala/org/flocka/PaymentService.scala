package org.flocka

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

//TODO maybe extend HttpApp
object PaymentService extends App {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("Flocka")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val service = "payment"

    val postPayPaymentRoute: Route = {
      pathPrefix(service /  "pay" / LongNumber / LongNumber ) { (userId, orderId) ⇒
        post{
          pathEndOrSingleSlash {
            complete("User  " + userId + " pays for " + orderId)
          }
        }
      }
    }

    val postCancelPaymentRoute: Route = {
      pathPrefix(service /  "cancelPayment" / LongNumber / LongNumber ) { (userId, orderId) ⇒
        post{
          pathEndOrSingleSlash {
            complete("User  " + userId + " cancels payment for " + orderId)
          }
        }
      }
    }

    val getGetPaymentStatusRoute: Route = {
      pathPrefix(service /  "status" / LongNumber ) { orderId ⇒
        get{
          pathEndOrSingleSlash {
            complete("get status of " + orderId)
          }
        }
      }
    }

    def route : Route = postPayPaymentRoute ~ postCancelPaymentRoute ~ getGetPaymentStatusRoute

    val host = "0.0.0.0"
    val port = 9000
    val bindingFuture = Http().bindAndHandle(route, host, port)

    bindingFuture.onComplete {
      case Success(serverBinding) => println(s"listening to ${serverBinding.localAddress}")
      case Failure(error) => println(s"error: ${error.getMessage}")
    }
  }
}