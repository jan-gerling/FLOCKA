package org.flocka.ServiceBasics

import akka.actor.Actor
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
  * Base class for all supervisors to be implemented in every service to propagate changes for our supervisor behavior.
  * Please adjust/ extend supervisorStrategy and timeout according to your needs.
  */
abstract class ActorSupervisorBase extends Actor with ActorLookup with CommandHandler with QueryHandler {
  def actorId = self.path.name

  //ToDo: what is a good timeout time for a useractor?
  context.setReceiveTimeout(6000 seconds)

  implicit def executor: ExecutionContext = context.dispatcher

  /*
  ToDo: find a good timeout time
   */
  def timeoutTime = 400 millisecond;
  implicit def timeout = Timeout(timeoutTime)
}