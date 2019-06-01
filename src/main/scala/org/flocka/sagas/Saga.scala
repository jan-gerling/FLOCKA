package org.flocka.sagas

import akka.actor.{ActorSystem}
import org.flocka.ServiceBasics.MessageTypes.Event
import org.flocka.sagas.SagaComs._
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
  * In this case we aren't using true sagas, because not all DAGs can be represented. For our purposes,
  * this is more than enough however, and we avoid adding even more dependencies.
  *
  * Did not choose to perform builder pattern as these are hard to implement safely in scala
  *
  * @param sagaId The id for this saga, not to be confused with the operation id.
  */
class Saga(sagaId: Long) {
  val id: Long = sagaId

  var dagOfOps: scala.collection.mutable.ArrayBuffer[scala.collection.mutable.ArrayBuffer[SagaOperation]] =  scala.collection.mutable.ArrayBuffer(new scala.collection.mutable.ArrayBuffer())
  var currentState = SagaState.IDLE

  var maxIndex: Int = dagOfOps.size -1
  var currentIndex: Int = 0

  def addConcurrentOperation(operation: SagaOperation): Unit = {
    dagOfOps.last += operation
  }

  def addSequentialOperation(operation: SagaOperation): Unit = {
    val newOperations: scala.collection.mutable.ArrayBuffer[SagaOperation] = scala.collection.mutable.ArrayBuffer(operation)
    dagOfOps += newOperations
    maxIndex = dagOfOps.size - 1

  }

  /**
    * @return Is this Saga already finished - either Successful or Aborted?
    */
  def isFinished: Boolean = {
    return ( currentState == SagaState.SUCCESS && currentIndex == maxIndex + 1) || (currentState == SagaState.FAILURE && currentIndex < 0)
  }

  /**
    * For internal use only.
    * @return Is the Saga execution done and state can be changed?
    */
  private def completedExecution: Boolean = {
    return (currentState == SagaState.PENDING &&  currentIndex == maxIndex + 1) || ( currentState == SagaState.ROLLBACK && currentIndex < 0)
  }

  /**
    * @return the operations of the current step, only call during execution
    */
  private def currentOperations: scala.collection.mutable.ArrayBuffer[SagaOperation] = dagOfOps(currentIndex)

  /**
    * Execute this saga by executing each step sequentially.
    * @param executor execution context of the calling SEC
    * @param system actor system context of the calling SEC
    * @return an SagaAborted or SagaCompleted after finishing the Saga either by successfully executing all steps or reverting all executed steps
    */
  def execute()(implicit executor: ExecutionContext, system: ActorSystem): Event = {
    if (currentState ==  SagaState.FAILURE || currentState == SagaState.SUCCESS ){
      throw new IllegalAccessException("This Saga " + id + " is not supposed to be executed.")
    }
    currentState = SagaState.PENDING

    while(!completedExecution) {
      val stepSuccess = executeStep()

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
        throw new IllegalStateException("Saga " + currentState + " with index: " + currentIndex)
      }
    }

    if(currentState == SagaState.PENDING) {
      currentState = SagaState.SUCCESS
      return SagaCompleted(this)
    } else {
      currentState = SagaState.FAILURE
      return SagaFailed(this)
    }
  }

  private def executeStep()(implicit executor: ExecutionContext, system: ActorSystem): Boolean ={
    var pendingOperations: ListBuffer[SagaOperation] = ListBuffer[SagaOperation]()
    var failed = false
    for (currentOperation: SagaOperation <- currentOperations){
        if (currentOperation.isExecutable && currentState == SagaState.PENDING) {
          pendingOperations += currentOperation

          val future: Future[Any] = currentOperation.executeForward
          future.onComplete {
            case Success(true) =>
              pendingOperations -= currentOperation
            case Success(false) =>
              pendingOperations -= currentOperation
              failed = true
            case Failure(ex) => throw new Exception(ex)
            case _ => throw new IllegalArgumentException(future.value.toString)
          }
        } else if (currentOperation.isSuccess && currentState == SagaState.ROLLBACK) {
          pendingOperations += currentOperation

          val future: Future[Any] = currentOperation.executeRevert
          future.onComplete {
            case Success(true) =>
              pendingOperations -= currentOperation
            case Success(false) =>
              pendingOperations -= currentOperation
              failed = true
            case Failure(ex) => throw new Exception(ex)
            case _ => throw new IllegalArgumentException(future.value.toString)
          }
        }
      }

    while (pendingOperations.size > 0){Thread.sleep(25)}
    return !failed
  }
}