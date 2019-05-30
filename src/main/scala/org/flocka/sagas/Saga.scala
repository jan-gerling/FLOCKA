package org.flocka.sagas

import akka.actor.{ActorRef, ActorSystem}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.sagas.SagaExecutionControllerComs._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

//Possible State Machine states
object SagaState extends Enumeration {
  val IDLE, PENDING, ROLLBACK, SUCCESS, FAILURE = Value
}

/**
  * A saga is a directed acyclic graph of operations to be executed according to the SAGA protocol
  *
  * In this case we arent using true sagas, because not all DAGs can be represented. For our purposes,
  * this is more than enough however, and we avoid adding even more dependencies.
  *
  * Did not choose to perform builder pattern as these are hard to implement safely in scala
  */
class Saga(sagaId: Long) {
  val id: Long = sagaId

  var dagOfOps: scala.collection.mutable.ArrayBuffer[scala.collection.mutable.ArrayBuffer[SagaOperation]] =  scala.collection.mutable.ArrayBuffer(new scala.collection.mutable.ArrayBuffer())
  var currentState = SagaState.IDLE
  var currentIndex: Int = dagOfOps.size

  def addConcurrentOperation(operation: SagaOperation): Unit = {
    dagOfOps.last += operation
  }

  def addSequentialOperation(operation: SagaOperation): Unit = {
    val newOperations: scala.collection.mutable.ArrayBuffer[SagaOperation] = scala.collection.mutable.ArrayBuffer(operation)
    dagOfOps += newOperations
  }

  def isFinished: Boolean = {
    return ( currentState == SagaState.SUCCESS && currentIndex <= -1) || (currentState == SagaState.FAILURE && currentIndex == dagOfOps.size)
  }

  private def completedExecution: Boolean = {
    return (currentState == SagaState.PENDING && currentIndex <= -1) || ( currentState == SagaState.ROLLBACK && currentIndex == dagOfOps.size)
  }

  def currentOperations: scala.collection.mutable.ArrayBuffer[SagaOperation] = dagOfOps(currentIndex)

  def execute(controller: ActorRef)(implicit executor: ExecutionContext, system: ActorSystem): Event = {
    if (currentState ==  SagaState.FAILURE || currentState == SagaState.SUCCESS ){
      throw new IllegalAccessException("This Saga " + id + " is not supposed to be executed.")
    }

    while(!completedExecution) {
      val stepSuccess = executeStep(controller)

      //success
      if(stepSuccess && currentState == SagaState.PENDING){
        currentIndex += 1
      } else if(stepSuccess && currentState == SagaState.ROLLBACK){
        currentIndex -= 1
      }
      //failure
      else if (!stepSuccess && currentState == SagaState.PENDING){
        currentState = SagaState.ROLLBACK
      } else if (!stepSuccess && currentState == SagaState.ROLLBACK){
        throw new Exception("Could not revert done operation.")
      } else {
        throw new IllegalStateException()
      }
    }

    if(currentState == SagaState.PENDING) {
      return SagaCompleted()
    } else {
      return SagaAborted()
    }
  }

  private def executeStep(controller: ActorRef)(implicit executor: ExecutionContext, system: ActorSystem): Boolean ={
    var pendingOperations: ListBuffer[SagaOperation] = ListBuffer[SagaOperation]()
    var failed = false

    for (currentOperation: SagaOperation <- currentOperations){
      if(!currentOperation.isCompleted){
        pendingOperations += currentOperation

        var future: Future[Any] = Future("Upps")
        if(currentState == SagaState.PENDING){
          var future = currentOperation.doForward()
        } else if(currentState == SagaState.ROLLBACK){
          var future = currentOperation.doInvert()
        } else{
          throw new IllegalStateException(currentState + " is an illegal state here.")
        }

        future.onComplete {
          case Success(true) =>
            pendingOperations -= currentOperation
          case Success(false) =>
            pendingOperations -= currentOperation
            failed = true
          case Failure(ex) => throw new Exception(ex)
          case _ => throw new IllegalArgumentException()
        }
      }
    }
    while (pendingOperations.size > 0){Thread.sleep(25)}
    return !failed
  }
}