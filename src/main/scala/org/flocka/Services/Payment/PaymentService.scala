package org.flocka.Services.Payment

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics.{CommandHandler, MessageTypes, QueryHandler}
import org.flocka.Services.Payment.PaymentServiceComs._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


/**
  * Contains routes for the Rest Payment Service. Method bind is used to start the server.
  */
object PaymentService extends CommandHandler with QueryHandler {

  val randomGenerator: scala.util.Random  = scala.util.Random
  val service = "payment"
  val timeoutTime: FiniteDuration = 500 millisecond
  implicit val timeout: Timeout = Timeout(timeoutTime)

  /**
    * Starts the server
    * @param shardRegion the region behind which the
    * @param exposedPort the port in which to expose the service
    * @param executor jeez idk,
    * @param system the ActorSystem
    * @return
    */
  def bind(shardRegion: ActorRef, exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding] = {
    /*
      Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
      */
    def commandHandler(command: MessageTypes.Command): Future[Any] = {
      super.commandHandler(command, Option(shardRegion), timeoutTime, executor)
    }

    /*
      similar to the command handler
      */
    def queryHandler(query: MessageTypes.Query): Future[Any] = {
      super.queryHandler(query, Option(shardRegion), timeoutTime, executor)
    }

    val postPayPaymentRoute: Route = {
      pathPrefix(service /  "pay" / LongNumber / LongNumber ) { (userId, orderId) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(PayPayment(userId, orderId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postCancelPaymentRoute: Route = {
      pathPrefix(service /  "cancelPayment" / LongNumber / LongNumber ) { (userId, orderId) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(CancelPayment(userId, orderId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val getGetPaymentStatusRoute: Route = {
      pathPrefix(service /  "status" / LongNumber ) { orderId ⇒
        get{
          pathEndOrSingleSlash {
            onComplete(queryHandler(GetPaymentStatus(orderId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex)   => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    def route : Route = postPayPaymentRoute ~ postCancelPaymentRoute ~ getGetPaymentStatusRoute

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}