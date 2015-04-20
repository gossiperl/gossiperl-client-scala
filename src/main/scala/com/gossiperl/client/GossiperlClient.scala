package com.gossiperl.client

import java.util.UUID

import akka.actor.{ReceiveTimeout, Props, ActorSystem}
import akka.actor.ActorDSL._
import com.gossiperl.client.actors._
import com.gossiperl.client.exceptions.GossiperlClientException
import com.gossiperl.client.serialization.{DeserializeResult, Serializer, CustomDigestField}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object GossiperlClientProtocol {
  sealed trait ProtocolOp
  case class Accepted( config: OverlayConfiguration ) extends ProtocolOp
  case class Connected( config: OverlayConfiguration ) extends ProtocolOp
  case class Disconnected( config: OverlayConfiguration ) extends ProtocolOp
  case class Stopped( config: OverlayConfiguration ) extends ProtocolOp
  case class Error( config: OverlayConfiguration, message:String, reason: Throwable ) extends ProtocolOp
  case class Event( configuration: OverlayConfiguration, eventType: String, member: Any, heartbeat: Long ) extends ProtocolOp
  case class SubscribeAck( configuration: OverlayConfiguration, eventTypes: Seq[String] ) extends ProtocolOp
  case class UnsubscribeAck( configuration: OverlayConfiguration, eventTypes: Seq[String] ) extends ProtocolOp
  case class ForwardAck( configuration: OverlayConfiguration, replyId: String ) extends ProtocolOp
  case class Forward( configuration: OverlayConfiguration, digestType: String, binaryEnvelope: Array[Byte], envelopeId: String ) extends ProtocolOp
}

object GossiperlClient {

  val actorSystemName = "gossiperl-client"
  implicit val actorSystem = ActorSystem( actorSystemName )
  val supervisor = actorSystem.actorOf(Props[Supervisor], name=Supervisor.supervisorName)

}

trait GossiperlClient {

  import GossiperlClient._

  implicit val configuration = new OverlayConfiguration(
                                    overlayName = "implicit-overlay",
                                    clientName = "implicit-scala-client",
                                    clientSecret = UUID.randomUUID().toString,
                                    symmetricKey = UUID.randomUUID().toString,
                                    overlayPort = 6666,
                                    clientPort = 54321 )

  def event: PartialFunction[GossiperlClientProtocol.ProtocolOp, Unit]

  def connect():Unit = {
    supervisor ! SupervisorProtocol.Connect( configuration, this )
  }

  def disconnect():Unit = {
    supervisor ! SupervisorProtocol.Disconnect( configuration )
  }

  import akka.util.Timeout
  import concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(5 seconds)

  def currentState:Future[ FSMState.ClientState ] = {
    val p = Promise[ FSMState.ClientState ]
    val action = actor(new Act {
      context.setReceiveTimeout( timeout.duration )
      become {
        case "act" =>
          context.system.actorSelection(s"/user/${Supervisor.supervisorName}/${configuration.overlayName}/state") resolveOne() onComplete {
            case Success(a) => a ! FSMProtocol.CurrentStateRequest
            case Failure(ex) => p.failure(ex)
          }
        case ReceiveTimeout =>
          p.failure(new GossiperlClientException( s"Could not load current state for ${configuration.overlayName}. Request timed out." ))
          context.stop( self )
        case FSMProtocol.CurrentStateResponse( state ) =>
          p.success( state )
          context.stop( self )
      }
    })
    action ! "act"
    p.future
  }

  def subscriptions: Future[Seq[String]] = {
    val p = Promise[ Seq[String] ]
    val action = actor(new Act {
      context.setReceiveTimeout( timeout.duration )
      become {
        case "act" =>
          context.system.actorSelection(s"/user/${Supervisor.supervisorName}/${configuration.overlayName}/state") resolveOne() onComplete {
            case Success(a) => a ! FSMProtocol.CurrentSubscriptionsRequest
            case Failure(ex) => p.failure(ex)
          }
        case ReceiveTimeout =>
          p.failure(new GossiperlClientException( s"Could not load current subscriptions for ${configuration.overlayName}. Request timed out." ))
          context.stop( self )
        case FSMProtocol.CurrentSubscriptionsResponse( subs ) =>
          p.success( subs )
          context.stop( self )
      }
    })
    action ! "act"
    p.future
  }

  def subscribe( eventTypes: Seq[String] ):Future[Seq[String]] = {
    val p = Promise[ Seq[String] ]
    val action = actor(new Act {
      context.setReceiveTimeout( timeout.duration )
      become {
        case "act" =>
          context.system.actorSelection(s"/user/${Supervisor.supervisorName}/${configuration.overlayName}/state") resolveOne() onComplete {
            case Success(a) => a ! FSMProtocol.SubscribeRequest(eventTypes)
            case Failure(ex) => p.failure(ex)
          }
        case ReceiveTimeout =>
          p.failure(new GossiperlClientException( s"Could not request subscribe to $eventTypes on ${configuration.overlayName}. Request timed out." ))
          context.stop( self )
        case FSMProtocol.SubscribeResponse( respEventTypes ) =>
          p.success( respEventTypes )
          context.stop( self )
        case FSMProtocol.SubscribeResponseError( ex ) =>
          p.failure( ex )
          context.stop( self )
      }
    })
    action ! "act"
    p.future
  }

  def unsubscribe( eventTypes: Seq[String] ):Future[Seq[String]] = {
    val p = Promise[ Seq[String] ]
    val action = actor(new Act {
      context.setReceiveTimeout( timeout.duration )
      become {
        case "act" =>
          context.system.actorSelection(s"/user/${Supervisor.supervisorName}/${configuration.overlayName}/state") resolveOne() onComplete {
            case Success(a) => a ! FSMProtocol.UnsubscribeRequest(eventTypes)
            case Failure(ex) => p.failure(ex)
          }
        case ReceiveTimeout =>
          p.failure(new GossiperlClientException( s"Could not request unsubscribe from $eventTypes on ${configuration.overlayName}. Request timed out." ))
          context.stop( self )
        case FSMProtocol.UnsubscribeResponse( respEventTypes ) =>
          p.success( respEventTypes )
          context.stop( self )
        case FSMProtocol.UnsubscribeResponseError( ex ) =>
          p.failure( ex )
          context.stop( self )
      }
    })
    action ! "act"
    p.future
  }

  def send( digestType: String, digestData: Seq[CustomDigestField] ):Future[Tuple2[String, Seq[CustomDigestField]]] = {
    val p = Promise[Tuple2[String, Seq[CustomDigestField]]]
    val action = actor(new Act {
      context.setReceiveTimeout( timeout.duration )
      become {
        case "act" =>
          context.system.actorSelection(s"/user/${Supervisor.supervisorName}/${configuration.overlayName}/transport") resolveOne() onComplete {
            case Success(a) => a ! UdpTransportProtocol.SendCustom(digestType, digestData)
            case Failure(ex) => p.failure(ex)
          }
        case ReceiveTimeout =>
          p.failure(new GossiperlClientException( s"Could not send custom digest to ${configuration.overlayName}. Request timed out." ))
          context.stop( self )
        case UdpTransportProtocol.CustomDigestSent( dt, dd ) =>
          p.success( ( dt, dd ) )
          context.stop( self )
      }
    })
    action ! "act"
    p.future
  }

  def read( digestType: String, binDigest: Array[Byte], digestInfo: Seq[CustomDigestField] ):Try[DeserializeResult] = {
    import scala.collection.JavaConversions._
    Try[DeserializeResult] { new Serializer().deserializeArbitrary( digestType, binDigest, digestInfo.toList ) }
  }

}
