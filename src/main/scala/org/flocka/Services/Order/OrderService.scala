package org.flocka.Services.Order

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics._
import org.flocka.Services.Order.OrderServiceComs._
import org.flocka.sagas.{SECSharding, SagasExecutionControllerActor}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Contains routes for the Rest Order Service. Method bind is used to start the service.
  */
object OrderService extends ServiceBase {

  val randomGenerator: scala.util.Random  = scala.util.Random
  val service = "orders"
  val timeoutTime: FiniteDuration = 500 millisecond
  implicit val timeout: Timeout = Timeout(timeoutTime)

  /**
    * Starts the service
    * @param shardRegion the region behind which the
    * @param exposedPort the port in which to expose the service
    * @param executor jeez idk,
    * @param system the ActorSystem
    * @return
    */
  def bind(shardRegion: ActorRef, exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding] = {
    val regionalIdManager: IdGenerator = new IdGenerator()

    val SECShardRegion: ActorRef = ClusterSharding(system).start(
      typeName = SECSharding.shardName,
      entityProps = SagasExecutionControllerActor.props,
      settings = ClusterShardingSettings(system),
      extractEntityId = SECSharding.extractEntityId,
      extractShardId = SECSharding.extractShardId)

    /*
      Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
      Giving id -1 is no id, only for creating new objects
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

    def createNewOrder(userId: Long): Route ={
      //ToDO: fix number generation, because it is actually in range Long and should use UserIdManager.shardregion
      onComplete(commandHandler(CreateOrder(regionalIdManager.generateId(OrderSharding.numShards), userId))) {
        case Success(value) => complete(value.toString)
        case Failure(ex) =>
          if(ex.toString.contains("InvalidIdException")) {
            regionalIdManager.increaseEntropy()
            createNewOrder(userId)
          }
          else
            complete(s"An error occurred: ${ex.getMessage}")
      }
    }

    val postCreateOrderRoute: Route = {
      pathPrefix(service / "create" / LongNumber) { userId ⇒
        post {
          pathEndOrSingleSlash {
            createNewOrder(userId)
          }
        }
      }
    }

    val deleteRemoveOrderRoute: Route = {
      pathPrefix(service / "remove" / LongNumber) { orderId ⇒
        delete {
          pathEndOrSingleSlash {
            onComplete(commandHandler(DeleteOrder(orderId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val getFindOrderRoute: Route = {
      pathPrefix(service / "find" / LongNumber) { orderId ⇒
        get {
          pathEndOrSingleSlash {
            onComplete(queryHandler(FindOrder(orderId))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postAddItemRoute: Route = {
      pathPrefix(service / "item" / "add" / LongNumber / LongNumber / LongNumber.?) { (orderId, itemId, operationId) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(AddItem(orderId, itemId, operationId.getOrElse{-1L}))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postRemoveItemRoute: Route = {
      pathPrefix(service / "item" / "remove" / LongNumber / LongNumber / LongNumber.?) { (orderId, itemId, operationId) ⇒
        post {
          pathEndOrSingleSlash {
            onComplete(commandHandler(RemoveItem(orderId, itemId, operationId.getOrElse{-1L}))) {
              case Success(value) => complete(value.toString)
              case Failure(ex) => complete(s"An error occurred: ${ex.getMessage}")
            }
          }
        }
      }
    }

    val postCheckoutOrderRoute: Route = {
      pathPrefix(service / "checkout" / LongNumber) { (orderId) ⇒
        post {
          pathEndOrSingleSlash {
            //ToDo: add SEC for checkout order here
            throw new UnsupportedOperationException("The service checkout is not yet supported by " + getClass)
          }
        }
      }
    }

    def route: Route = postCreateOrderRoute ~ deleteRemoveOrderRoute ~ getFindOrderRoute ~
      postAddItemRoute ~ postRemoveItemRoute ~ postCheckoutOrderRoute

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }
}