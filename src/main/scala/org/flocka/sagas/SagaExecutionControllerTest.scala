package org.flocka.sagas

import java.net.URI

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.ClusterSharding
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import akka.pattern.ask
import org.flocka.ServiceBasics.IdGenerator
import org.flocka.Services.User.MockLoadbalancerService
import org.flocka.Utils.TimeMeasurement
import org.flocka.sagas.SagaExecutionControllerComs.ExecuteSaga
import java.util.concurrent.TimeUnit
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object SagaExecutionControllerTest extends App {
  def logImportant(toLog: String) = println("==========================\n" + toLog + "\n=============================")


  def startupSharedJournal(system: ActorSystem, startStore: Boolean, path: ActorPath)(implicit executionContext: ExecutionContext): Unit = {
    // Start the shared journal one one node (don't crash this SPOF)
    // This will not be needed with a distributed journal
    if (startStore) {
      system.actorOf(Props[SharedLeveldbStore], "store")
      logImportant("Started Journal!")
    }
    // register the shared journal
    implicit val timeout = Timeout(15.seconds)
    val f = system.actorSelection(path) ? Identify(None)
    f.onSuccess {
      case ActorIdentity(_, Some(ref)) =>
        SharedLeveldbJournal.setStore(ref, system)
        logImportant("Found journal!")
      case _ =>
        system.log.error("Shared journal not started at {}", path)
        system.terminate()
    }
    f.onFailure {
      case _ =>
        system.log.error("Lookup of shared journal at {} timed out", path)
        system.terminate()
    }
  }


  override def main(args: Array[String]): Unit = {
    Seq("2571", "2572") foreach { port =>

      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load("order-service.conf"))

      // Create an Akka system for each port
      implicit val system = ActorSystem(config getString "clustering.cluster.name", config)

      //Journal is currently leveldb, it is used to persist events of PersistentActors
      val pathToJournal: ActorPath = ActorPath.fromString("akka.tcp://" + config.getString("clustering.cluster.name") + "@" + config.getString("akka.remote.netty.tcp.hostname") + ":2571/user/store")
      startupSharedJournal(system, startStore = (port == "2571"), path = pathToJournal)(system.dispatcher)

      //Start sharding system locally, this will create a ShardingRegion
      SagaExecutionControllerSharding.startSharding(system)

      //sleep needed for proper boot of "cluster"
      Thread.sleep(5000)
      if (port == "2572") {
        attemptStartRest()
        Thread.sleep(2000)

        //test multiple sagas
        for(_ <- 1 to 3){
          logImportant("Sending saga to shardRegion")
          val testSaga = createOrderSaga()

          val timeoutTime: FiniteDuration = 3000 millisecond
          implicit val timeout: Timeout = Timeout(timeoutTime)
          implicit val executor: ExecutionContext = system.dispatcher

          val secShard = ClusterSharding(system).shardRegion(SagaExecutionControllerSharding.shardName)

          val t0: Long = System.nanoTime()
          val futureSaga = secShard ? ExecuteSaga(testSaga)
          futureSaga.onComplete{
            case Success(value) =>
              val elapsedTime: Double = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
              println(value + " took: " + elapsedTime + " " + TimeUnit.MICROSECONDS.toString)
          }
          Thread.sleep(3000)
        }
      }
    }
  }

  private def createOrderSaga(): Saga ={
    val config: Config = ConfigFactory.load("saga-execution-controller.conf")
    val loadBalancerURI: String = config.getString("clustering.loadbalancer.uri")

    val idGenerator: IdGenerator = new IdGenerator()
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

  def attemptStartRest()(implicit system: ActorSystem): Unit = {
    //Start rest service
    MockLoadbalancerService.bind(8080, system.dispatcher).onComplete(
      Success => logImportant("Started server!")
    )(system.dispatcher)
  }
}