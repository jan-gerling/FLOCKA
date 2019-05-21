package org.flocka.Services.User

import akka.actor.{Actor, Props}

// ToDo: implement validator properly
/**
This Object stores the props to create a UserActorSupervisor.
 */
object Validator{
  /**
  Props used to create a new UserActorSupervisor.
  "Props is a configuration class to specify options for the creation of actors."
  For more details on props look here: https://doc.akka.io/docs/akka/2.5.5/scala/actors.html
   */
  def props(): Props = Props(new Validator())
}

/**
  * Supervisor or guardian for a range of user actors, implementing the ActorSupervisorBase class.
  * Responsibilities: create ids for new user, map userid on useractor, send command/ query to the correct user actor and pipe the result back to the service, validate user actor response for a command/ query
  */
class Validator extends Actor {
  /**
  Receive the commands here and process them, to keep the communication running.
 */
  override def receive: Receive = {
    case command @ _  => throw new IllegalArgumentException(command.toString)

    /*
    case command @ CreateUser(-1) =>
      val userId = generateUserId(extractSupervisorId(actorId.toLong))
      commandHandler( command, userId, sender(),
        _ match {
          case UserServiceComs.UserCreated(resultId) => userId == resultId
          case _ => false
        }
      )
    case command @ DeleteUser(userId) =>
      commandHandler( command, userId, sender(),
        _ match {
          case UserServiceComs.UserDeleted(resultId, status) => userId == resultId && status
          case _ => false
        })
    case query @ FindUser(userId) =>
      queryHandler( query, userId, sender(),
        _ match {
          case UserServiceComs.UserFound(resultId, _) => userId == resultId
          case _ => false
        })
    case query @ GetCredit(userId) =>
      queryHandler( query, userId, sender(),
        _ match {
          case UserServiceComs.UserDeleted(resultId, _) => userId == resultId
          case _ => false
        })
    case command @ AddCredit(userId, amount) =>
      commandHandler( command, userId, sender(),
        _ match {
          case UserServiceComs.CreditAdded(resultId, resultAmount, _) => userId == resultId && resultAmount == amount
          case _ => false
        })
    case command @ SubtractCredit(userId, amount) =>
      commandHandler( command, userId, sender(),
        _ match {
          case UserServiceComs.CreditSubtracted(resultId, resultAmount, _) => userId == resultId && resultAmount == amount
          case _ => false
        })
  */
  }
}