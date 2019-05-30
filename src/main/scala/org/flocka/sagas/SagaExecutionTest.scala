package org.flocka.sagas

import java.net.URI
import java.util.concurrent.TimeUnit
import akka.pattern.AskTimeoutException
import org.flocka.ServiceBasics.IdGenerator
import org.flocka.sagas.SagaComs.{ExecuteSaga, SagaCompleted, SagaFailed}
import akka.http.scaladsl.server.Directives.{pathEndOrSingleSlash, pathPrefix, post}
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.flocka.ServiceBasics._
import org.flocka.Services.User.MockLoadbalancerService
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Contains routes for the Rest Order Service. Method bind is used to start the service.
  */
object SagaExecutionTest extends ServiceBase {
  override val configName: String = "saga-execution-controller.conf"
  val service = "orders"
  val timeoutTime: FiniteDuration = 500 millisecond
  implicit val timeout: Timeout = Timeout(timeoutTime)

  def logImportant(toLog: String) = println("==========================\n" + toLog + "\n=============================")

  def bind(shardRegion: ActorRef, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding] = {
    implicit val executionContext: ExecutionContext = executor
    attemptStartRest()

    val idGenerator: IdGenerator = new IdGenerator()
    val loadBalancerURI: String = config.getString("clustering.loadbalancer.uri")

    /**
      Handles the given command for supervisor actor by sending it with the ask pattern to the target actor.
      Giving id -1 is no id, only for creating new objects
      */
    def commandHandler(command: MessageTypes.Command): Future[Any] = {
      super.commandHandler(command, Option(shardRegion), timeoutTime, executor)
    }

    def createOrderSaga(): Saga ={
      val id: Long = idGenerator.generateId(100)
      val orderSaga: Saga = new Saga(id)

      val payPostCondition: String => Boolean = new Function[String, Boolean] {
        override def apply(v1: String): Boolean = return v1.contains("pay")
      }
      val decStockPostCondition:String => Boolean = new Function[String, Boolean] {
        override def apply(v1: String): Boolean = return v1.contains("decreased")
      }

      val so1: SagaOperation = new SagaOperation(URI.create(loadBalancerURI + "/lb/pay/1/1"), URI.create(loadBalancerURI + "/lb/cancelPayment/1/1"), payPostCondition)
      val so2: SagaOperation = new SagaOperation(URI.create(loadBalancerURI + "/lb/subtract/1/1"), URI.create(loadBalancerURI + "/lb/add/1/1"), decStockPostCondition)
      val so3: SagaOperation = new SagaOperation(URI.create(loadBalancerURI + "/lb/subtract/1/1"), URI.create(loadBalancerURI + "/lb/add/1/1"), decStockPostCondition)

      val so4: SagaOperation = new SagaOperation(URI.create(loadBalancerURI + "/lb/pay/1/1"), URI.create(loadBalancerURI + "/lb/cancelPayment/1/1"), payPostCondition)
      val so5: SagaOperation = new SagaOperation(URI.create(loadBalancerURI + "/lb/subtract/1/1"), URI.create(loadBalancerURI + "/lb/add/1/1"), decStockPostCondition)

      orderSaga.addConcurrentOperation(so1)
      orderSaga.addConcurrentOperation(so2)
      orderSaga.addConcurrentOperation(so3)

      orderSaga.addSequentialOperation(so4)
      orderSaga.addConcurrentOperation(so5)

      return orderSaga
    }

    val postCheckoutOrderRoute: Route = {
      pathPrefix(service / "checkout" / LongNumber) { (orderId) ⇒
        post {
          pathEndOrSingleSlash {
            val t0: Long = System.nanoTime()
            val saga: Saga = createOrderSaga()
            onComplete(commandHandler(ExecuteSaga(saga))) {
              case Success(value: SagaCompleted) =>
                val elapsedTime: Double = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                val response: String = "Successful Saga: " + value.saga.id + " took: " + elapsedTime + " " + TimeUnit.MILLISECONDS.toString
                complete(response)
              case Success(value: SagaFailed) =>
                val elapsedTime: Double = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                val response: String = "Failed Saga: " + value.saga.id + " took: " + elapsedTime + " " + TimeUnit.MILLISECONDS.toString
                complete(response)
              case Failure(ex: AskTimeoutException) =>
                val elapsedTime: Double = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                val response: String = "Timed out Saga: " + saga.id + " took: " + elapsedTime + " " + TimeUnit.MILLISECONDS.toString
                complete(response)
            }
          }
        }
      }
    }

    def route: Route = postCheckoutOrderRoute

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(route, "0.0.0.0", exposedPort)
  }

  def attemptStartRest()(implicit system: ActorSystem): Unit = {
    //Start rest service
    MockLoadbalancerService.bind( 8080, system.dispatcher).onComplete(
      Success => logImportant("Started server!")
    )(system.dispatcher)
  }
}