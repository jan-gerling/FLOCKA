package org.flocka.ServiceBasics

import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.{ask, pipe}

/**
Implements a handler for all commands between actors.
  */
trait CommandHandler {
  /**
  Handles the given command for an actor by sending it with the ask pattern to the target actor.
  @param command command for an data base objects value
  @param targetActor the target actor for the given command
  @param timeoutTime timeout time for this command
  @param currentExecutor current execution context of the actor
  @param pipeToActor if the current actor is not supposed to retrieve the result, pipe it to this actor
  @param postConditions if wanted apply conditions for the results of the given command
  @return is supposed to be future of type UserCommunication.Event.
    */
    def commandHandler(command: MessageTypes.Command,
                       targetActor: Option[ActorRef],
                       timeoutTime: FiniteDuration,
                       currentExecutor: ExecutionContext,
                       pipeToActor: Option[ActorRef] = None,
                       postConditions: Any => Boolean = _ => true) : Future[Any] = {
      implicit val timeout = Timeout(timeoutTime)
      implicit val executor: ExecutionContext = currentExecutor
      targetActor match {
      case Some(actorRef: ActorRef) =>
        val actorFuture = actorRef ? command
        //ToDo: how to handle unexpected/ unwanted results?
        //ToDo: how to handle exceptions? aside from supervisor strategies?
        actorFuture.filter (postConditions).recover {
          case m: NoSuchElementException => 0
        }

        pipeToActor match {
          case Some(receivingActor) => actorFuture pipeTo receivingActor
          case None => return actorFuture
        }
      case None => throw new IllegalArgumentException(command.toString)
      }
  }
}
