package org.flocka.ServiceBasics

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http.ServerBinding
import scala.concurrent.{ExecutionContext, Future}

/**
  * Basic class definition for every service. It gives you all basic functionality you will need for a service.
  * Please extend this trait when you implement a service, for reference see UserService.scala in Services.User, so we can propagate changes easily throughout our architecture.
  * Please check the documentation for the specific traits for more details.
 */
trait ServiceBase extends CommandHandler with QueryHandler {
  def bind(shardRegion: ActorRef, exposedPort: Int, executor: ExecutionContext)(implicit system: ActorSystem): Future[ServerBinding]
}