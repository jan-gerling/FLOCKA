package org.flocka.sharding

import org.flocka.MessageTypes

object UserServiceComs {
  /*
  /users/create/
    POST - returns an ID
  */
  final case class CreateUser(objectId: Long) extends MessageTypes.Command
  final case class UserCreated(objectId: Long) extends MessageTypes.Event

  /*
  /users/remove/{user_id}
    DELETE - return success/failure
  */
  final case class DeleteUser(objectId: Long) extends MessageTypes.Command
  final case class UserDeleted(objectId: Long, success: Boolean) extends MessageTypes.Event

  /*
  /users/find/{user_id}
    GET - returns a set of users with their details (id, and credit)
  */
  final case class FindUser(objectId: Long) extends MessageTypes.Query
  final case class UserFound(objectId: Long, userDetails: Set[Long]) extends MessageTypes.Event

  /*
  /users/credit/{user_id}
    GET - returns the current credit of a user
  */
  final case class GetCredit(objectId: Long) extends MessageTypes.Query
  final case class CreditGot(objectId: Long, credit: Long) extends MessageTypes.Event

  /*
  /users/credit/subtract/{user_id}/{amount}
    POST - subtracts the amount from the credit of the user (e.g., to buy an order). Returns success or failure, depending on the credit status.
  */
  final case class SubtractCredit(objectId: Long, amount: Long) extends MessageTypes.Command
  final case class CreditSubtracted(objectId: Long, amount: Long, success: Boolean) extends MessageTypes.Event

  /*
  /users/credit/add/{user_id}/{amount}
    POST - subtracts the amount from the credit of the user. Returns success or failure, depending on the credit status.
  */
  final case class AddCredit(objectId: Long, amount: Long) extends MessageTypes.Command
  final case class CreditAdded(objectId: Long, amount: Long, success: Boolean) extends MessageTypes.Event
}