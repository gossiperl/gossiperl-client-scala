package com.gossiperl.client.actors

import akka.actor.{ActorRef, Actor}
import com.typesafe.scalalogging.slf4j.LazyLogging

import scala.concurrent.{Future, Promise}

class ActorNotRegisteredException(message: String) extends RuntimeException(message)

object ActorRegistry extends LazyLogging {

  private val reg = new java.util.concurrent.ConcurrentHashMap[String, ActorRef]

  def actorRegister(name: String, ref: ActorRef):Unit = {
    logger.debug(s"${getClass.getName}: Registering actor $name as $ref")
    reg put( name, ref )
  }

  def actorUnregister(name: String):Unit = {
    logger.debug(s"${getClass.getName}: Deregistering actor $name")
    reg remove name
  }

  def getRef( name: String ):Option[ActorRef] = {
    Option(reg get name)
  }

}

trait ActorRegistry extends Actor {

  def registerLocal():Unit = {
    ActorRegistry.actorRegister( self.path.name, self )
  }

  def unregisterLocal():Unit = {
    ActorRegistry.actorUnregister( self.path.name )
  }

  override def preStart():Unit = {
    registerLocal()
  }

  override def postStop():Unit = {
    unregisterLocal()
  }

  def !:( name: String, message: Any):Future[ActorRef] = {
    val p = Promise[ActorRef]
    ActorRegistry.getRef( name ) match {
      case Some(a) =>
        a ! message
        p.success(a)
      case None =>
        p.failure(new ActorNotRegisteredException( name ))
    }
    p.future
  }

}
