package com.gossiperl.client.actors

import java.util.UUID

import akka.actor.{FSM, ActorLogging}
import com.gossiperl.client.serialization.Serializers
import com.gossiperl.client.thrift.{DigestUnsubscribe, DigestSubscribe, Digest, DigestExit}
import com.gossiperl.client.{GossiperlClientProtocol, Util, OverlayConfiguration}

import scala.util.{Failure, Success, Try}

object FSMState {

  sealed trait ClientState
  case object ClientStateConnected extends ClientState
  case object ClientStateDisconnected extends ClientState

}

object FSMProtocol {

  case object Tick
  case object CurrentStateRequest
  case object CurrentSubscriptionsRequest

  case class CurrentStateResponse( state: FSMState.ClientState )
  case class CurrentSubscriptionsResponse( eventTypes: Seq[String] )
  case class SubscribeRequest(eventTypes:Seq[String])
  case class UnsubscribeRequest(eventTypes:Seq[String])
  case class SubscribeResponse(eventTypes:Seq[String])
  case class SubscribeResponseError( error: Throwable )
  case class UnsubscribeResponse(eventTypes:Seq[String])
  case class UnsubscribeResponseError( error: Throwable )

}

class StateData( val configuration:OverlayConfiguration,
                       val subscriptions:Seq[String] = Seq.empty,
                       val lastAck:Long = 0) {
  override def toString() = {
    s"ClientStateData(configuration=${configuration.toString}, subsciptions=${subscriptions.toString}, lastAck=${lastAck})"
  }
}

class State( config: OverlayConfiguration ) extends FSM[FSMState.ClientState, StateData] with ActorEx with ActorLogging {

  log.debug(s"State for ${config.overlayName} running.")

  import akka.util.Timeout
  import concurrent.duration._
  implicit val timeout = Timeout(1 seconds)

  startWith(FSMState.ClientStateDisconnected, new StateData(config))
  setTimer("Communicate", FSMProtocol.Tick, 1 second, repeat = true)

  object SubscriptionAction extends Enumeration {
    type SubscriptionAction = Value
    val Subscribe, Unsubscribe = Value
  }
  import SubscriptionAction._

  when (FSMState.ClientStateDisconnected) {
    case Event(MessagingProtocol.AckReceived, currentState) =>
      goto(FSMState.ClientStateConnected) using new StateData(config, currentState.subscriptions, System.currentTimeMillis)
    case Event(FSMProtocol.Tick, _) =>
      communicate
      stay
  }

  when (FSMState.ClientStateConnected) {
    case Event(MessagingProtocol.AckReceived, currentState) =>
      stay using new StateData(config, currentState.subscriptions, System.currentTimeMillis)
    case Event(FSMProtocol.Tick, lastKnownState) =>
      communicate
      (System.currentTimeMillis - lastKnownState.lastAck) match {
        case x if x > 5000L => goto(FSMState.ClientStateDisconnected)
        case _ => stay
      }
  }

  whenUnhandled {
    case Event(FSMProtocol.CurrentStateRequest, stateData) =>
      sender ! FSMProtocol.CurrentStateResponse(stateName)
      stay
    case Event(FSMProtocol.CurrentSubscriptionsRequest, stateData) =>
      sender ! FSMProtocol.CurrentSubscriptionsResponse( stateData.subscriptions )
      stay
    case Event(FSMProtocol.SubscribeRequest(eventTypes), currentStateData) =>
      Try(subscriptionAction(Subscribe, eventTypes)) match {
        case Success(_) =>
          sender ! FSMProtocol.SubscribeResponse( eventTypes )
          stay using new StateData(config, (currentStateData.subscriptions ++ eventTypes).distinct.sorted, System.currentTimeMillis)
        case Failure(ex) =>
          sender ! FSMProtocol.SubscribeResponseError( ex )
          stay
      }
    case Event(FSMProtocol.UnsubscribeRequest(eventTypes), currentStateData) =>
      Try(subscriptionAction(Unsubscribe, eventTypes)) match {
        case Success(_) =>
          sender ! FSMProtocol.UnsubscribeResponse( eventTypes )
          stay using new StateData(config, ( currentStateData.subscriptions diff eventTypes ), System.currentTimeMillis)
        case Failure(ex) =>
          sender ! FSMProtocol.UnsubscribeResponseError( ex )
          stay
      }
    case Event(OverlayWorkerProtocol.Disconnect, _) =>
      val digest = new DigestExit()
      digest.setHeartbeat( Util.getTimestamp )
      digest.setName( config.clientName )
      digest.setSecret( config.clientSecret )
      log.debug("Received shutdown request. Offering digestExit...")
      !:(s"/user/${Supervisor.supervisorName}/${config.overlayName}/transport", UdpTransportProtocol.Disconnect( digest ) )
      goto(FSMState.ClientStateDisconnected)
  }

  onTransition {
    case FSMState.ClientStateDisconnected -> FSMState.ClientStateConnected =>
      log.debug(s"Connected -> ${nextStateData}.")
      !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Connected( config ) ) )
    case FSMState.ClientStateConnected -> FSMState.ClientStateDisconnected =>
      log.debug("Connection lost.")
      !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Disconnected( config ) ) )
  }

  private def communicate():Unit = {
    val digest = new Digest
    digest.setSecret( config.clientSecret )
    digest.setId( UUID.randomUUID().toString )
    digest.setHeartbeat( Util.getTimestamp )
    digest.setPort( config.clientPort )
    digest.setName( config.clientName )
    log.debug(s"Offering digest ${digest.getId}")
    !:( s"/user/${Supervisor.supervisorName}/${config.overlayName}/transport", UdpTransportProtocol.SendThrift( digest ) )
  }

  private def subscriptionAction( action : SubscriptionAction, eventTypes : Seq[String] ) : Unit = {
    import scala.collection.JavaConversions._
    val digest: Serializers.Thrift = action match {
      case SubscriptionAction.Subscribe =>
        val digest = new DigestSubscribe()
        digest.setEvent_types( eventTypes.toList )
        digest.setHeartbeat( Util.getTimestamp )
        digest.setId( UUID.randomUUID().toString )
        digest.setName( config.clientName )
        digest.setSecret( config.clientSecret )
        digest
      case SubscriptionAction.Unsubscribe =>
        val digest = new DigestUnsubscribe()
        digest.setEvent_types( eventTypes.toList )
        digest.setHeartbeat( Util.getTimestamp )
        digest.setId( UUID.randomUUID().toString )
        digest.setName( config.clientName )
        digest.setSecret( config.clientSecret )
        digest
    }
    log.debug(s"Offering subscription digest ${digest}")
    !:( s"/user/${Supervisor.supervisorName}/${config.overlayName}/transport", UdpTransportProtocol.SendThrift( digest ) )
  }

}
