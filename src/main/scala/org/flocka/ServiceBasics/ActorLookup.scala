package org.flocka.ServiceBasics

import akka.actor.{ActorContext, ActorRef, ActorRefFactory, Props}
import scala.collection.mutable

/**
  * Hides the actor ref lookup from all the other functions, always use this as an endpoint to get actor refs by actorId.
  * Call getActor to get an Option[ActorRef].
  */
//ToDo: Find distributed implementation of actorRef lookups maybe delegating this already to temporary actors?
trait ActorLookup {
  var knownChildren: mutable.Map[String, ActorRef] = mutable.Map.empty

  /**
    * Get the reference to an actor if it exists and is a child of the current context, otherwise creates a new actor with the given props and factory.
    * @param actorId the id of the actor you are looking for
    * @param context the context of the current
    * @param props use this to create a new actor for the given id
    */
  def getActor(actorId: String, context: ActorContext, props: Props): Option[ActorRef] = {
    getChild(actorId, context) match{
      case Some(actorRef: ActorRef) => return Some(actorRef)
      case None => Some(createActor(actorId, context, props))
    }
  }

  /**
    * Get the reference to an actor if it exists and was created by the current actor, otherwise creates a new actor with the given props and factory.
    * @param actorId the id of the actor you are looking for
    * @param factory the factory you would use to create a new actor, either ActorSystem or ActorContext
    * @param props use this to create a new actor for the given id
    */
  def getActor(actorId: String, factory: ActorRefFactory, props: Props): Option[ActorRef] = {
    getKnownChild(actorId) match{
      case Some(actorRef: ActorRef) => return Some(actorRef)
      case None => Some(createActor(actorId, factory, props))
    }
  }

  /**
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

  /**
  Create a new actor with the given userId.
  NEVER CALL THIS except you really know what you are doing and have a good reason. Use actorHandler instead.
   */
  protected def createActor(actorId: String, factory: ActorRefFactory, props: Props): ActorRef ={
    var actorRef = factory.actorOf(props, actorId)
    knownChildren  += actorId -> actorRef
    return actorRef
  }
}
