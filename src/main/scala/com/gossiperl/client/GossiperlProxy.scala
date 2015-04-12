package com.gossiperl.client

import akka.actor.{ActorRef, ActorLogging, Actor}
import akka.util.Timeout
import com.gossiperl.client.SupervisorProtocol.SupervisorAction

import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

import concurrent.duration._

object GossiperlProxyProtocol {
  case class Connected( configuration: OverlayConfiguration )
  case class Disconnected( configuration: OverlayConfiguration )
  case class Event( configuration: OverlayConfiguration, eventType: String, member: Any, heartbeat: Long )
  case class SubscribeAck( configuration: OverlayConfiguration, eventTypes: Seq[String] )
  case class UnsubscribeAck( configuration: OverlayConfiguration, eventTypes: Seq[String] )
  case class ForwardAck( configuration: OverlayConfiguration, replyId: String )
  case class Forward( configuration: OverlayConfiguration, digestType: String, binaryEnvelope: Array[Byte], envelopeId: String )
  case class Error( configuration: OverlayConfiguration, error: Throwable )

  case class ShutdownRequest( p: Promise[ActorRef] )
}

class GossiperlProxy( val configuration: OverlayConfiguration, p: Promise[GossiperlProxy] ) extends Actor with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(1 seconds)

  // Yes, we do want it like this. We want the proxy to be available as an instance to the caller so we can simply forward events to it.
  // Proxy is used from outside of the actor system.
  p.success(this)

  var listenerAction : Any => Unit = ( arg => log.debug( arg.toString ) )

  def event( f: Any => Unit ):Unit = {
    listenerAction = f
  }

  def receive = {
    case GossiperlProxyProtocol.Connected( config ) =>
      listenerAction.apply( GossiperlProxyProtocol.Connected( config ) )
    case GossiperlProxyProtocol.Disconnected( config ) =>
      listenerAction.apply( GossiperlProxyProtocol.Disconnected( config ) )
    case GossiperlProxyProtocol.Event( config, evType, evObj, heartbeat ) =>
      listenerAction.apply( GossiperlProxyProtocol.Event( config, evType, evObj, heartbeat ) )
    case GossiperlProxyProtocol.SubscribeAck( config, evTypes ) =>
      listenerAction.apply( GossiperlProxyProtocol.SubscribeAck( config, evTypes ) )
    case GossiperlProxyProtocol.UnsubscribeAck( config, evTypes ) =>
      listenerAction.apply( GossiperlProxyProtocol.UnsubscribeAck( config, evTypes ) )
    case GossiperlProxyProtocol.ForwardAck( config, replyId ) =>
      listenerAction.apply( GossiperlProxyProtocol.ForwardAck( config, replyId ) )
    case GossiperlProxyProtocol.Forward( config, digestType, binaryEnvelope, envelopeId ) =>
      listenerAction.apply( GossiperlProxyProtocol.Forward( config, digestType, binaryEnvelope, envelopeId ) )
    case GossiperlProxyProtocol.Error( config, err ) =>
      listenerAction.apply( GossiperlProxyProtocol.Error( config, err ) )
    case GossiperlProxyProtocol.ShutdownRequest( p ) =>
      log.debug("Received shutdown request.")
      p.success(self)
      context.stop( self )
    case any => log.error(s"Unsupported client message: $any")
  }

  def disconnect :Unit = {
    action( SupervisorProtocol.Disconnect( configuration.overlayName ) ) onFailure {
      case ex => log.error("Disconnect failed. Supervisor not started.", ex)
    }
  }

  def currentState: Future[Option[FSMState.ClientState]] = {
    val p = Promise[Option[FSMState.ClientState]]
    action( SupervisorProtocol.CheckState( configuration.overlayName, p ) ) onFailure {
      case ex =>
        log.debug("Current status check failed. Supervisor not started.", ex)
        p.failure(ex)
    }
    p.future
  }

  def subscriptions :Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    action( SupervisorProtocol.Subscriptions( configuration.overlayName, p ) ) onFailure {
      case ex =>
        log.debug("Current subscriptions check failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def subscribe( eventTypes: Seq[String] ):Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    action( SupervisorProtocol.Subscribe( configuration.overlayName, eventTypes, p ) ) onFailure {
      case ex =>
        log.error(s"Subscribe to $eventTypes failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  def unsubscribe( eventTypes: Seq[String] ):Future[Option[Seq[String]]] = {
    val p = Promise[Option[Seq[String]]]
    action( SupervisorProtocol.Unsubscribe( configuration.overlayName, eventTypes, p ) ) onFailure {
      case ex =>
        log.error(s"Unsubscribe from $eventTypes failed. Supervisor not started.", ex)
        p.failure( ex )
    }
    p.future
  }

  private def action( action: SupervisorAction ):Future[Boolean] = {
    val p = Promise[Boolean]; context.system.actorSelection(s"/user/${Supervisor.actorName}").resolveOne() onComplete {
      case Success(a) =>  a ! action ; p.success(true)
      case Failure(ex) => p.failure(ex)
    }; p.future
  }

}
