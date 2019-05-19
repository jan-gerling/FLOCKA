package org.flocka.ServiceBasics

import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.Stop
import akka.persistence.PersistentActor
import akka.util.Timeout
import org.flocka.Services.User.{UserActor}
import org.flocka.Services.User.UserActor.UserActorTimeoutException
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
  * Base class for all supervisors to be implemented in every service to propagate changes for our supervisor behavior.
  * Please adjust/ extend supervisorStrategy and timeout according to your needs.
  * You might want to adjust/ extend receiveRecover and snapShotInterval as well, but please verify if it is really necessary.
  */
abstract class PersistentSupervisorBase extends PersistentActor with ActorLookup with CommandHandler with QueryHandler {
  override def persistenceId = self.path.name

  def receiveRecover: Receive = {
    case _ =>
  }

  implicit def executor: ExecutionContext = context.dispatcher

  /*
  ToDo: Learn more about the different strategies
  Default strategy for unmatched exceptions is escalation
  https://doc.akka.io/docs/akka/current/fault-tolerance.html#creating-a-supervisor-strategy
 */
  override def supervisorStrategy = OneForOneStrategy() {
    case ex: UserActor.InvalidUserException => Stop
    case ex: UserActorTimeoutException => Stop
  }

  /*
  ToDo: find a good timeout time
   */
  def timeoutTime = 400 millisecond;
  implicit def timeout = Timeout(timeoutTime)

  //TODO figure out good interval value for snapshots
  def snapShotInterval = 1000

  val receiveCommand: Receive
}
