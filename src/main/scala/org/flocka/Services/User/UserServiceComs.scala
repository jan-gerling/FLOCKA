package org.flocka.Services.User

import org.flocka.MessageTypes

object UserServiceComs {
  /*
  /users/create/
    POST - returns an ID
  */
  final case class CreateUser() extends MessageTypes.Command
  final case class UserCreated(userId: Long) extends MessageTypes.Event

  /*
  /users/remove/{user_id}
    DELETE - return success/failure
  */
  final case class DeleteUser(userID: Long) extends MessageTypes.Command
  final case class UserDeleted(userId: Long, success: Boolean) extends MessageTypes.Event

  /*
  /users/find/{user_id}
    GET - returns a set of users with their details (id, and credit)
  */
  final case class FindUser(userID: Long) extends MessageTypes.Query
  final case class UserFound(userId: Long, userDetails: Set[Long]) extends MessageTypes.Event

  /*
  /users/credit/{user_id}
    GET - returns the current credit of a user
  */
  final case class GetCredit(userID: Long) extends MessageTypes.Query
  final case class CreditGot(userId: Long, credit: Long) extends MessageTypes.Event

  /*
  /users/credit/subtract/{user_id}/{amount}
    POST - subtracts the amount from the credit of the user (e.g., to buy an order). Returns success or failure, depending on the credit status.
  */
  final case class SubtractCredit(userID: Long, amount: Long) extends MessageTypes.Command
  final case class CreditSubtracted(userId: Long, amount: Long, success: Boolean) extends MessageTypes.Event

  /*
  /users/credit/add/{user_id}/{amount}
    POST - subtracts the amount from the credit of the user. Returns success or failure, depending on the credit status.
  */
  final case class AddCredit(userID: Long, amount: Long) extends MessageTypes.Command
  final case class CreditAdded(userId: Long, amount: Long, success: Boolean) extends MessageTypes.Event
}