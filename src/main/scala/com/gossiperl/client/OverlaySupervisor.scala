package com.gossiperl.client

import akka.actor.{Props, ActorLogging, Actor}

class OverlayConfiguration( val overlayName:String,
                            val clientName:String,
                            val clientSecret:String,
                            val symmetricKey:String,
                            val overlayPort:Int,
                            val clientPort:Int ) {
  override def toString() = {
    s"OverlayConfiguration(overlayName=${overlayName}, clientName=${clientName}, clientSecret=<protected>, symmetricKey=<protected>, overlayPort=${overlayPort}, clientPort=${clientPort})"
  }
}

case class RequestShutdown()
case class OverlayShutdownComplete(configuration:OverlayConfiguration)

class OverlaySupervisor(val configuration:OverlayConfiguration) extends Actor with ActorLogging {

  log.debug(s"Overlay ${configuration.overlayName} is running.")

  context.actorOf(Props( new ClientStateFSM(configuration) ), name = s"${configuration.overlayName}-client-state")

  def receive = {
    case RequestShutdown() =>
      log.debug("Received shutdown request...")

      log.debug("Gracefully stopping self.")
      context.stop(self)
  }

  override def postStop():Unit = {
    log.debug("Sending OverlayShutdownComplete to the parent.")
    context.parent ! OverlayShutdownComplete(configuration)
  }

}
