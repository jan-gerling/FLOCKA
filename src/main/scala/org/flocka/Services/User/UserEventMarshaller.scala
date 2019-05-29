package org.flocka.Services.User

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.flocka.Services.User.UserServiceComs._
import spray.json.{DefaultJsonProtocol, DeserializationException, JsNumber, JsObject, JsValue, RootJsonFormat}

trait UserEventMarshaller extends SprayJsonSupport with DefaultJsonProtocol {
  //implicit val userCreatedFormat = jsonFormat(UserCreated, "userId")
  implicit val userDeletedFormat = jsonFormat(UserDeleted, "userId", "success")
  implicit val creditGotFormat = jsonFormat(CreditGot, "userId", "credit")
  implicit val creditSubtractedFormat = jsonFormat(CreditSubtracted, "userId", "amount", "success", "operation")
  implicit val creditAddedFormat = jsonFormat(CreditSubtracted, "userId", "amount", "success", "operation")


  implicit object UserCreatedFormat extends RootJsonFormat[UserCreated] {
    def write(e: UserCreated) = JsObject(
      "userId" -> JsNumber(e.userId)
    )
    def read(value: JsValue) = {
      value.asJsObject.getFields("userId") match {
        case Seq(JsNumber(userId)) =>
          new UserCreated(userId.toLong)
        case _ => throw new DeserializationException("UserCreated expected")
      }
    }
  }
}
