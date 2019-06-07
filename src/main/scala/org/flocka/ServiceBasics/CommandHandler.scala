package org.flocka.ServiceBasics

import akka.actor.ActorRef
import akka.util.Timeout
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
  @param pipeToActor if the current actor is not supposed to retrieve the result, pipe it to this actor
  @return is supposed to be future of type UserCommunication.Event.
    */
    def commandHandler(command: MessageTypes.Command,
                       targetActor: Option[ActorRef],
                       pipeToActor: Option[ActorRef] = None)
                      (implicit timeout: Timeout, executor: ExecutionContext): Future[Any] = {
      targetActor match {
      case Some(actorRef: ActorRef) =>
        val actorFuture = actorRef ? command
        pipeToActor match {
          case Some(receivingActor) =>
            actorFuture pipeTo receivingActor
          case None => return actorFuture
        }
      case None =>
        throw new IllegalArgumentException(command.toString)
      }
  }
}
