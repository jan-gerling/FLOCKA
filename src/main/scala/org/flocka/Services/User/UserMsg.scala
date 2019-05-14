package org.flocka.Services.User

import akka.actor.{ActorLogging, Props}
import akka.persistence.{PersistentActor, SnapshotOffer}


/*
Define all messages foe very single command implemented by user
1. command message
2.1 successful response message
2.2 failure response message
 */

object UserMsg {
  sealed trait Cmd
  sealed trait Event
  sealed trait Response

  /*
  /users/create/
    POST - returns an ID
  */
  final case class CreateUser() extends Cmd
  final case class UserCreated(userId: Long) extends Event

  /*
  /users/remove/{user_id}
    DELETE - return success/failure
  */
  final case class DeleteUser(userID: Long) extends Cmd
  final case class UserDeleted(userId: Long, success: Boolean) extends Event

  /*
  /users/find/{user_id}
    GET - returns a set of users with their details (id, and credit)
  */
  final case class FindUser(userID: Long) extends Cmd
  final case class UserFound(userId: Long, userDetails: Set[Long]) extends Event

  /*
  /users/credit/{user_id}
    GET - returns the current credit of a user
  */
  final case class GetCredit(userID: Long) extends Cmd
  final case class CreditGot(userId: Long, credit: Long) extends Event

  /*
  /users/credit/subtract/{user_id}/{amount}
    POST - subtracts the amount from the credit of the user (e.g., to buy an order). Returns success or failure, depending on the credit status.
  */
  final case class SubtractCredit(userID: Long, amount: Long) extends Cmd
  final case class CreditSubtracted(userId: Long, amount: Long, success: Boolean) extends Event

  /*
  /users/credit/add/{user_id}/{amount}
    POST - subtracts the amount from the credit of the user. Returns success or failure, depending on the credit status.
  */
  final case class AddCredit(userID: Long, amount: Long) extends Cmd
  final case class CreditAdded(userId: Long, amount: Long, success: Boolean) extends Event
}