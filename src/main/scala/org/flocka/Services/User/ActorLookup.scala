package org.flocka.Services.User

import akka.actor.{ActorContext, ActorRef, ActorRefFactory, Props}

import scala.collection.mutable

trait ActorLookup {
  var knownChildren: mutable.Map[String, ActorRef] = mutable.Map.empty

  /*
  Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by actorId.
  ToDo: Find distributed implementation of actorRef lookups maybe delegating this already to temporary actors?
  Get the reference to an existing actor.
   */
  def getActor(actorId: String, context: ActorContext, factory: ActorRefFactory, props: Props): Option[ActorRef] = {
    getChild(actorId, context) match{
      case Some(actorRef: ActorRef) => return Some(actorRef)
      case None => Some(createActor(actorId, factory, props))
    }
  }

  def getActor(actorId: String, factory: ActorRefFactory, props: Props): Option[ActorRef] = {
    getKnownChild(actorId) match{
      case Some(actorRef: ActorRef) => return Some(actorRef)
      case None => Some(createActor(actorId, factory, props))
    }
  }

  /*
  Find an existing actor child for this actor based on the actor id.
   */
  protected def getChild(actorId: String, context: ActorContext): Option[ActorRef] ={
    return  Some(context.child(actorId)).getOrElse{
      return getKnownChild(actorId)
    }
  }

  protected def getKnownChild(actorId: String) : Option[ActorRef] ={
    return knownChildren.get(actorId)
  }

  /*
  Create a new actor with the given userId.
  NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
   */
  protected def createActor(actorId: String, factory: ActorRefFactory, props: Props): ActorRef ={
    var actorRef = factory.actorOf(props, actorId)
    knownChildren  += actorId -> actorRef
    return actorRef
  }
}
