package com.gossiperl.client

import akka.actor.{ActorLogging, FSM}
import concurrent.duration._
import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}
import FSMState._
import FSMProtocol._

object FSMState {

  sealed trait ClientState

  case object ClientStateConnected extends ClientState

  case object ClientStateDisconnected extends ClientState

}

object FSMProtocol {

  sealed trait ClientProtocol

  case object AckReceived extends ClientProtocol

  case object RequestStop extends ClientProtocol

  case class RequestSubscriptions(p:Promise[Option[Seq[String]]]) extends ClientProtocol

  case class RequestCurrentState( p:Promise[Option[ClientState]]) extends ClientProtocol

  case class RequestSubscribe(eventTypes:Seq[String], p:Promise[Option[Seq[String]]]) extends ClientProtocol

  case class RequestUnsubscribe(eventTypes:Seq[String], p:Promise[Option[Seq[String]]]) extends ClientProtocol

  case object Tick extends ClientProtocol

}

class ClientStateData( val configuration:OverlayConfiguration,
                       val subscriptions:Seq[String] = Seq.empty,
                       val lastAck:Long = 0) {
  override def toString() = {
    s"ClientStateData(configuration=${configuration.toString}, subsciptions=${subscriptions.toString}, lastAck=${lastAck})"
  }
}

class ClientStateFSM(val configuration: OverlayConfiguration) extends FSM[ClientState, ClientStateData] with ActorLogging {

  object SubscriptionAction extends Enumeration {
    type SubscriptionAction = Value
    val Subscribe, Unsubscribe = Value
  }
  import SubscriptionAction._

  log.debug(s"Client state FSM ${configuration.overlayName} is running.")
  startWith(ClientStateDisconnected, new ClientStateData(configuration))
  setTimer("Communicate", Tick, 1 second, repeat = true)

  when (ClientStateDisconnected) {
    case Event(AckReceived, currentState) =>
      goto(ClientStateConnected) using new ClientStateData(configuration, currentState.subscriptions, System.currentTimeMillis)
    case Event(Tick, _) =>
      communicate
      stay
  }

  when (ClientStateConnected) {
    case Event(AckReceived, currentState) =>
      stay using new ClientStateData(configuration, currentState.subscriptions, System.currentTimeMillis)
    case Event(Tick, lastKnownState) =>
      communicate
      (System.currentTimeMillis - lastKnownState.lastAck) match {
        case x if x > 5000L => goto(ClientStateDisconnected)
        case _ => stay
      }
  }

  whenUnhandled {
    case Event(RequestStop, _) =>
      log.debug( "Shutdown requested." )
      stop()
    case Event(RequestSubscriptions( p ), currentStateData) =>
      p.success(Some(currentStateData.subscriptions))
      stay
    case Event(RequestCurrentState( p ), _) =>
      p.success(Some(stateName))
      stay
    case Event(RequestSubscribe(eventTypes, p), currentStateData) =>
      Try(subscriptionAction(Subscribe, eventTypes)) match {
        case Success(_) =>
          p.success( Some ( eventTypes ) )
          stay using new ClientStateData(configuration, (currentStateData.subscriptions ++ eventTypes).distinct.sorted, System.currentTimeMillis)
        case Failure(ex) =>
          p.failure( ex )
          stay
      }
    case Event(RequestUnsubscribe(eventTypes, p), currentStateData) =>
      Try(subscriptionAction(Unsubscribe, eventTypes)) match {
        case Success(_) =>
          p.success( Some(eventTypes) )
          stay using new ClientStateData(configuration, ( currentStateData.subscriptions diff eventTypes ), System.currentTimeMillis)
        case Failure(ex) =>
          p.failure( ex )
          stay
      }
  }

  onTransition {
    case ClientStateDisconnected -> ClientStateConnected =>
      log.debug(s"Connected -> ${nextStateData}.")
    case ClientStateConnected -> ClientStateDisconnected =>
      log.debug("Connection lost.")
  }

  private def communicate():Unit = {
    // TODO: implement
  }

  private def subscriptionAction( action : SubscriptionAction, eventTypes : Seq[String] ) : Boolean = {
    // TODO: implement
    true
  }

}
