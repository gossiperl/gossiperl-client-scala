package com.gossiperl.client

import java.util.UUID

import akka.actor.{ActorRef, ActorLogging, FSM}
import akka.util.Timeout
import com.gossiperl.client.actors.ActorEx
import com.gossiperl.client.serialization.Serializers
import com.gossiperl.client.thrift.{DigestUnsubscribe, DigestSubscribe, DigestExit, Digest}
import com.gossiperl.client.transport.UdpTransportProtocol
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

  case class RequestSubscriptions(p:Promise[Option[Seq[String]]]) extends ClientProtocol

  case class RequestCurrentState( p:Promise[Option[ClientState]]) extends ClientProtocol

  case class RequestSubscribe(eventTypes:Seq[String], p:Promise[Option[Seq[String]]]) extends ClientProtocol

  case class RequestUnsubscribe(eventTypes:Seq[String], p:Promise[Option[Seq[String]]]) extends ClientProtocol

  case object Tick extends ClientProtocol

}

class StateData( val configuration:OverlayConfiguration,
                       val subscriptions:Seq[String] = Seq.empty,
                       val lastAck:Long = 0) {
  override def toString() = {
    s"ClientStateData(configuration=${configuration.toString}, subsciptions=${subscriptions.toString}, lastAck=${lastAck})"
  }
}

class State(val configuration: OverlayConfiguration) extends FSM[ClientState, StateData] with ActorEx with ActorLogging {

  implicit val timeout = Timeout(1 seconds)
  import scala.concurrent.ExecutionContext.Implicits.global

  object SubscriptionAction extends Enumeration {
    type SubscriptionAction = Value
    val Subscribe, Unsubscribe = Value
  }
  import SubscriptionAction._

  log.debug(s"Client state FSM ${configuration.overlayName} is running.")
  startWith(ClientStateDisconnected, new StateData(configuration))
  setTimer("Communicate", Tick, 1 second, repeat = true)



  when (ClientStateDisconnected) {
    case Event(AckReceived, currentState) =>
      goto(ClientStateConnected) using new StateData(configuration, currentState.subscriptions, System.currentTimeMillis)
    case Event(Tick, _) =>
      communicate
      stay
  }

  when (ClientStateConnected) {
    case Event(AckReceived, currentState) =>
      log.debug("Received an ACK")
      stay using new StateData(configuration, currentState.subscriptions, System.currentTimeMillis)
    case Event(Tick, lastKnownState) =>
      communicate
      (System.currentTimeMillis - lastKnownState.lastAck) match {
        case x if x > 5000L => goto(ClientStateDisconnected)
        case _ => stay
      }
  }

  whenUnhandled {
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
          stay using new StateData(configuration, (currentStateData.subscriptions ++ eventTypes).distinct.sorted, System.currentTimeMillis)
        case Failure(ex) =>
          p.failure( ex )
          stay
      }
    case Event(RequestUnsubscribe(eventTypes, p), currentStateData) =>
      Try(subscriptionAction(Unsubscribe, eventTypes)) match {
        case Success(_) =>
          p.success( Some(eventTypes) )
          stay using new StateData(configuration, ( currentStateData.subscriptions diff eventTypes ), System.currentTimeMillis)
        case Failure(ex) =>
          p.failure( ex )
          stay
      }
    case Event(GossiperlProxyProtocol.ShutdownRequest( p ), _) =>
      log.debug("Received shutdown request. Preparing DigestExit.")
      val digest = new DigestExit()
      digest.setHeartbeat( Util.getTimestamp )
      digest.setName( configuration.clientName )
      digest.setSecret( configuration.clientSecret )
      // handle state promise
      val p2 = Promise[ActorRef]
      log.debug("Offering DigestExit.")
      !:( s"/user/${Supervisor.actorName}/${configuration.overlayName}/transport", UdpTransportProtocol.SendThrift( digest, Some( p2 ) ) ) onFailure {
        case ex =>
          log.error(s"Could not send digest exit. Messaging transport not found. Proceeding with shutdown.", ex)
          p.failure(ex)
          context.stop(self)
      }
      p2.future.onComplete {
        case Success(_) =>
          p.success(self)
          context.stop(self)
        case Failure(ex) =>
          log.error("There was an error while sending digest exit. Proceeding with shutdown.", ex)
          p.failure(ex)
          context.stop(self)
      }
      goto(ClientStateDisconnected)
  }

  onTransition {
    case ClientStateDisconnected -> ClientStateConnected =>
      log.debug(s"Connected -> ${nextStateData}.")
      !:( s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.Connected( configuration ) )
    case ClientStateConnected -> ClientStateDisconnected =>
      log.debug("Connection lost.")
      !:( s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.Disconnected( configuration ) )
  }

  private def communicate():Unit = {
    val digest = new Digest
    digest.setSecret( configuration.clientSecret )
    digest.setId( UUID.randomUUID().toString )
    digest.setHeartbeat( Util.getTimestamp )
    digest.setPort( configuration.clientPort )
    digest.setName( configuration.clientName )
    log.debug(s"Offering digest ${digest.getId}")
    !:( s"/user/${Supervisor.actorName}/${configuration.overlayName}/transport", UdpTransportProtocol.SendThrift( digest ) )
  }

  private def subscriptionAction( action : SubscriptionAction, eventTypes : Seq[String] ) : Unit = {
    import scala.collection.JavaConversions._
    val digest: Serializers.Thrift = action match {
      case SubscriptionAction.Subscribe =>
        val digest = new DigestSubscribe()
        digest.setEvent_types( eventTypes.toList )
        digest.setHeartbeat( Util.getTimestamp )
        digest.setId( UUID.randomUUID().toString )
        digest.setName( configuration.clientName )
        digest.setSecret( configuration.clientSecret )
        digest
      case SubscriptionAction.Unsubscribe =>
        val digest = new DigestUnsubscribe()
        digest.setEvent_types( eventTypes.toList )
        digest.setHeartbeat( Util.getTimestamp )
        digest.setId( UUID.randomUUID().toString )
        digest.setName( configuration.clientName )
        digest.setSecret( configuration.clientSecret )
        digest
    }
    log.debug(s"Offering subscription digest ${digest}")
    !:( s"/user/${Supervisor.actorName}/${configuration.overlayName}/transport", UdpTransportProtocol.SendThrift( digest ) )
  }

}
