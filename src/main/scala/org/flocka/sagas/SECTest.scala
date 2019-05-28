package org.flocka.sagas

import akka.actor.{ActorIdentity, ActorPath, ActorSystem, Identify, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.model.HttpResponse
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import akka.pattern.ask
import org.flocka.ServiceBasics.{IdGenerator, IdManager}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.Services.User.{MockLoadbalancerService, UserService, UserSharding}
import org.flocka.sagas.SagaExecutionControllerComs.{Execute, LoadSaga}
import sun.java2d.xr.XIDGenerator

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}


object SECTest extends App {
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
      val pathToJournal : ActorPath =  ActorPath.fromString("akka.tcp://" + config.getString("clustering.cluster.name")+ "@"+config.getString("akka.remote.netty.tcp.hostname")+":2571/user/store")
      startupSharedJournal(system, startStore = (port == "2571"), path =pathToJournal)(system.dispatcher)


      //Start sharding system locally, this will create a ShardingRegion
      SECSharding.startSharding(system)

      //sleep needed for proper boot of "cluster"
      Thread.sleep(5000)
      if(port == "2572"){
        attemptStartRest()
        Thread.sleep(2000)
        logImportant("Sending saga to shardRegion")
        val testSaga: Saga = new Saga()

        val payPostCondition = (x : HttpResponse) => x.entity.toString.contains("pays")
        val decStockPostCondition = (x : HttpResponse) => x.entity.toString.contains("decreased")
        val so1: SagaOperation = new SagaOperation("/lb/pay/1/1", "/lb/cancelPayment/1/1", payPostCondition)
        val so2: SagaOperation = new SagaOperation("/lb/subtract/1/1", "/lb/add/1/1", decStockPostCondition)
        val so3: SagaOperation = new SagaOperation("/lb/subtract/1/1", "/lb/add/1/1", decStockPostCondition)

        val so4: SagaOperation = new SagaOperation("/lb/pay/1/1", "/lb/cancelPayment/1/1", payPostCondition)
        val so5: SagaOperation = new SagaOperation("/lb/subtract/1/1", "/lb/add/1/1", decStockPostCondition)

        testSaga.addConcurrentOperation(so1)
        testSaga.addConcurrentOperation(so2)
        testSaga.addConcurrentOperation(so3)

        testSaga.addSequentialOperation(so4)
        testSaga.addConcurrentOperation(so5)

        val timeoutTime: FiniteDuration = 500 millisecond
        implicit val timeout: Timeout = Timeout(timeoutTime)
        implicit val executor: ExecutionContext = system.dispatcher

        val secShard = ClusterSharding(system).shardRegion(SECSharding.shardName)
        val idGenerator: IdGenerator = new IdGenerator()
        val id : Long = idGenerator.generateId(100)
        secShard ! LoadSaga(id, testSaga)
         secShard ! Execute(id)

      }
    }



  }
  def attemptStartRest()(implicit system: ActorSystem): Unit = {
      //Start rest service
      MockLoadbalancerService.bind( 8080, system.dispatcher).onComplete(
        Success => logImportant("Started server!")
      )(system.dispatcher)
  }
}
