package org.flocka.ServiceBasics

import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.Stop
import akka.persistence.PersistentActor
import akka.util.Timeout
import org.flocka.Services.User.{UserActor}
import org.flocka.Services.User.UserActor.UserActorTimeoutException
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

abstract class PersistentSupervisorBase extends PersistentActor with ActorLookup with CommandHandler with QueryHandler {
  override def persistenceId = self.path.name

  val receiveRecover: Receive = {
    case _ =>
  }

  implicit val executor: ExecutionContext = context.dispatcher

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
  val timeoutTime = 400 millisecond;
  implicit val timeout = Timeout(timeoutTime)

  //TODO figure out good interval value for snapshots
  val snapShotInterval = 1000

  val receiveCommand: Receive
}
