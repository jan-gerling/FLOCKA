package org.flocka.sharding

import akka.actor.ActorRef
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import org.flocka.MessageTypes

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

trait QueryHandler {
  /*
   Handles the given command for an actor by sending it with the ask pattern to the target actor.
   Returns actually a future of type UserCommunication.Event.
  */
  def queryHandler(query: MessageTypes.Query,
                     targetActor: Option[ActorRef],
                     timeoutTime: FiniteDuration,
                     currentExecutor: ExecutionContext,
                     pipeToActor: Option[ActorRef] = None,
                     postConditions: Any => Boolean = _ => true) : Future[Any] = {
    implicit val timeout = Timeout(timeoutTime)
    implicit val executor: ExecutionContext = currentExecutor
    targetActor match {
      case Some(actorRef: ActorRef) =>
        val actorFuture = actorRef ? query
        //ToDo: how to handle unexpected/ unwanted results?
        //ToDo: how to handle exceptions? aside from supervisor strategies?
        actorFuture.filter (postConditions).recover {
          case m: NoSuchElementException => 0
        }

        pipeToActor match {
          case Some(receivingActor) => actorFuture pipeTo receivingActor
          case None => return actorFuture
        }
      case None => throw new IllegalArgumentException(query.toString)
    }
  }
}
