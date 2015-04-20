package com.gossiperl.client.actors

import akka.actor.SupervisorStrategy.Escalate
import akka.actor.{Props, OneForOneStrategy, ActorLogging}
import com.gossiperl.client.OverlayConfiguration

object OverlayWorkerProtocol {

  case object Disconnect
  case class Stopped(config: OverlayConfiguration)

}

class OverlayWorker( config: OverlayConfiguration ) extends ActorEx with ActorLogging {

  import scala.concurrent.duration._

  val transport = context.actorOf(Props( new UdpTransport( config ) ), name = "transport")
  val messaging = context.actorOf(Props( new Messaging( config ) ), name = "messaging")
  val state = context.actorOf(Props( new State( config ) ), name = "state")

  log.debug(s"Overlay worker for ${config.overlayName} running.")

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10 seconds) {
    case _:Exception => Escalate
  }

  def receive = {
    case UdpTransportProtocol.Stopped =>
      log.debug("Transport is stopped. Asking messaging to shutdown...")
      messaging ! OverlayWorkerProtocol.Disconnect
    case MessagingProtocol.Stopped =>
      log.debug("Messaging is stopped. Shutting down state and self...")
      context.parent ! SupervisorProtocol.OverlayStopped(config)
      context.system.stop( state )
      context.system.stop( self )
    case OverlayWorkerProtocol.Disconnect =>
      log.debug("Received shutdown request. Asking state to send exit message...")
      state ! OverlayWorkerProtocol.Disconnect
  }

}
