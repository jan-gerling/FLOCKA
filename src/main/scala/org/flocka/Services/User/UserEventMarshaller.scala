package org.flocka.Services.User

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.sun.xml.internal.ws.encoding.soap.DeserializationException
import org.flocka.Services.User.UserServiceComs._
import spray.json.{DefaultJsonProtocol, JsBoolean, JsNumber, JsObject, JsString, JsValue, RootJsonFormat}

trait UserEventMarshaller extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object UserCreatedFormat extends RootJsonFormat[UserCreated] {

    def write(e: UserCreated) = JsObject(
      "event" -> JsString(UserCreated.getClass.getSimpleName),
      "userId" -> JsNumber(e.userId)
    )
    def read(value: JsValue) = {
      val event = fromField[String](value, "event")
      if(!UserCreated.getClass.getSimpleName.equals(event)) {
        throw new DeserializationException("UserCreated expected")
      }

      val userId = fromField[Long](value, "userId")
      UserCreated(userId)
    }
  }

  implicit object UserDeletedFormat extends RootJsonFormat[UserDeleted] {
    def write(e: UserDeleted) = JsObject (
      "event" -> JsString(UserDeleted.getClass.getSimpleName),
      "userId" -> JsNumber(e.userId),
      "success" -> JsBoolean(e.success)
    )
    def read(value: JsValue) = {
      val event = fromField[String](value, "event")
      if(!UserDeleted.getClass.getSimpleName.equals(event)) {
        throw new DeserializationException("UserDeleted expected")
      }
      val userId = fromField[Long](value, "userId")
      val success = fromField[Boolean](value, "success")
      UserDeleted(userId, success)
    }
  }

  implicit object CreditGotFormat extends RootJsonFormat[CreditGot] {
    def write(e: CreditGot) = JsObject (
      "event" -> JsString(CreditGot.getClass.getSimpleName),
      "userId" -> JsNumber(e.userId),
      "credit" -> JsNumber(e.credit)
    )
    def read(value: JsValue) = {
      val event = fromField[String](value, "event")
      if(!CreditGot.getClass.getSimpleName.equals(event)) {
        throw new DeserializationException("CreditGot expected")
      }
      val userId = fromField[Long](value, "userId")
      val credit = fromField[Long](value, "credit")
      CreditGot(userId, credit)
    }
  }

  implicit object CreditSubtractedFormat extends RootJsonFormat[CreditSubtracted] {
    def write(e: CreditSubtracted) = JsObject (
      "event" -> JsString(CreditSubtracted.getClass.getSimpleName),
      "userId" -> JsNumber(e.userId),
      "amount" -> JsNumber(e.amount),
      "success" -> JsBoolean(e.success),
      "operation" -> JsNumber(e.operation)
    )
    def read(value: JsValue) = {
      val event = fromField[String](value, "event")
      if(!CreditSubtracted.getClass.getSimpleName.equals(event)) {
        throw new DeserializationException("CreditSubtracted expected")
      }
      val userId = fromField[Long](value, "userId")
      val amount = fromField[Long](value, "amountt")
      val success = fromField[Boolean](value, "success")
      val operation = fromField[Long](value, "operation")
      CreditSubtracted(userId, amount, success, operation)
    }
  }

  implicit object CreditAddedFormat extends RootJsonFormat[CreditAdded] {
    def write(e: CreditAdded) = JsObject (
      "event" -> JsString(CreditAdded.getClass.getSimpleName),
      "userId" -> JsNumber(e.userId),
      "amount" -> JsNumber(e.amount),
      "success" -> JsBoolean(e.success),
      "operation" -> JsNumber(e.operation)
    )
    def read(value: JsValue) = {
      val event = fromField[String](value, "event")
      if(!CreditAdded.getClass.getSimpleName.equals(event)) {
        throw new DeserializationException("CreditAdded expected")
      }
      val userId = fromField[Long](value, "userId")
      val amount = fromField[Long](value, "amountt")
      val success = fromField[Boolean](value, "success")
      val operation = fromField[Long](value, "operation")
      CreditAdded(userId, amount, success, operation)
    }
  }
}
