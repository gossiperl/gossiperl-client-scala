package com.gossiperl.client

import akka.actor.{ActorLogging, FSM}
import concurrent.duration._

sealed trait ClientState
case object Connected extends ClientState
case object Disconnected extends ClientState

sealed trait ClientProtocol
case object AckReceived extends ClientProtocol
case object Tick extends ClientProtocol
case object RequestStop extends ClientProtocol

class ClientStateData( val configuration:OverlayConfiguration,
                       val subscriptions:Seq[String] = Seq.empty,
                       val lastAck:Long = 0) {
  override def toString() = {
    s"ClientStateData(configuration=${configuration.toString}, subsciptions=${subscriptions.toString}, lastAck=${lastAck})"
  }
}

class ClientStateFSM(val configuration: OverlayConfiguration) extends FSM[ClientState, ClientStateData] with ActorLogging {

  log.debug(s"Client state FSM ${configuration.overlayName} is running.")
  startWith(Disconnected, new ClientStateData(configuration))
  setTimer("Communicate", Tick, 1 second, repeat = true)

  when (Disconnected) {
    case Event(AckReceived, currentState) =>
      goto(Connected) using new ClientStateData(configuration, currentState.subscriptions, System.currentTimeMillis)
    case Event(Tick, _) =>
      communicate
      stay
  }

  when (Connected) {
    case Event(AckReceived, currentState) =>
      stay using new ClientStateData(configuration, currentState.subscriptions, System.currentTimeMillis)
    case Event(Tick, lastKnownState) =>
      communicate
      (System.currentTimeMillis - lastKnownState.lastAck) match {
        case x if x > 5000L => goto(Disconnected)
        case _ => stay
      }
  }

  whenUnhandled {
    case Event(RequestStop, _) =>
      log.debug( "Shutdown requested." )
      stop()
  }

  onTransition {
    case Disconnected -> Connected =>
      log.debug(s"Connected -> ${nextStateData}.")
    case Connected -> Disconnected =>
      log.debug("Connection lost.")
  }

  private def communicate():Unit = {
    //log.info("communicating...")
  }

}
