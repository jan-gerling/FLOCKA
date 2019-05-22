package org.flocka.Services.Payment

import java.util.ServiceConfigurationError

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{complete, get, pathEndOrSingleSlash, pathPrefix, post}
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.actor.ActorRef
import org.flocka.MessageTypes
import org.flocka.ServiceBasics.ServiceBase
import org.flocka.Services.User.{UserActorSupervisor, UserIdManager}
import org.flocka.Services.User.UserService.{extractSupervisorId, supervisorIdRange}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//TODO maybe extend HttpApp
object PaymentService extends ServiceBase with PaymentIdManager {

  override def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("Flocka")
    implicit val executor: ExecutionContext = system.dispatcher
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val randomGenerator = scala.util.Random
    val service = "payment"
    val timeoutTime: FiniteDuration = 500 millisecond
    implicit val timout: Timeout = Timeout(timeoutTime)


    /*
    TODO make comment
     */
    def commandHandler(command: MessageTypes.Command, orderId: Long): Future[Any] = {
      super.commandHandler(command, getPayment(orderId), timeoutTime, executor)
    }

    /*
    TODO make comment
     */
    def queryHandler(query: MessageTypes.Query, orderId: Long): Future[Any] = {
      super.queryHandler(query, getPayment(orderId), timeoutTime, executor)
    }

    /*
    Get the actor reference for the supervisor for the given userid.
    Inherited from ActorLookup
     */
    def getPayment(orderId: Long): Option[ActorRef] ={
      var supervisorId: Long = -1
      orderId match {
        case _ => supervisorId = extractSupervisorId(orderId)
      }
      return super.getActor(supervisorId.toString, system, PaymentActorSupervisor.props())
    }

    /*
    TODO figure out ID
     */
    val postPayPaymentRoute: Route = {
      pathPrefix(service /  "pay" / LongNumber / LongNumber ) { (userId, orderId) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(PayPayment(userId, orderId), orderId)) {
              case Success(value) => complete(value.toString)
              case Failure(ex)    => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    /*
    TODO figure out ID
     */
    val postCancelPaymentRoute: Route = {
      pathPrefix(service /  "cancelPayment" / LongNumber / LongNumber ) { (userId, orderId) ⇒
        post{
          pathEndOrSingleSlash {
            onComplete(commandHandler(CancelPayment(userId, orderId), orderId)) {
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
            onComplete(queryHandler(GetPaymentStatus(orderId), orderId)) {
              case Sucess(value) => complete(value.toString)
              case Failure(ex)   => complete(s"An error occurred: ${ex.getMessage}")
            }
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
