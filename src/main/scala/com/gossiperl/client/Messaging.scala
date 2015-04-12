package com.gossiperl.client

import java.util

import akka.actor.{Props, ActorLogging, Actor}
import akka.util.Timeout
import com.gossiperl.client.exceptions.GossiperlClientException
import com.gossiperl.client.serialization.{Serializer, DeserializeResultForward, DeserializeResultError, DeserializeResultOK}
import com.gossiperl.client.thrift._
import com.gossiperl.client.transport.{UdpTransportProtocol, UdpTransport}
import scala.collection.JavaConverters._

import scala.util.{Failure, Success}

class Messaging(val configuration: OverlayConfiguration) extends Actor with ActorLogging {

  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  context.actorOf(Props( new UdpTransport(configuration) ), name = s"${configuration.overlayName}-transport")

  log.debug(s"Messaging for overlay ${configuration.overlayName} is running.")

  implicit val timeout = Timeout(1 seconds)

  def receive = {
    case GossiperlProxyProtocol.ShutdownRequest(p) =>
      log.debug("Received shutdown request.")
      p.success(self)
      context.stop( self )
    case UdpTransportProtocol.IncomingData(result) =>
      result match {
        case _:DeserializeResultOK =>
          result.asInstanceOf[DeserializeResultOK].getDigestType match {
            case Serializer.DIGEST =>
              digestAck( result.asInstanceOf[DeserializeResultOK].getDigest.asInstanceOf[ Digest ] )
            case Serializer.DIGEST_ACK =>
              context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}/${configuration.overlayName}-client-state") resolveOne() onComplete {
                case Success(a)  => a ! FSMProtocol.AckReceived
                case Failure(ex) => log.warning("Could not notify digest ack. No client state.")
              }
            case Serializer.DIGEST_EVENT =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestEvent ]
              context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
                case Success(a)  => a ! GossiperlProxyProtocol.Event( configuration, digest.getEvent_type, digest.getEvent_object, digest.getHeartbeat )
                case Failure(ex) => log.warning("Could not notify incoming event. No proxy.")
              }
            case Serializer.DIGEST_SUBSCRIBE_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestSubscribeAck ]
              context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
                case Success(a)  => a ! GossiperlProxyProtocol.SubscribeAck( configuration, digest.getEvent_types.asScala )
                case Failure(ex) => log.warning("Could not notify incoming subscribe ack. No proxy.")
              }
            case Serializer.DIGEST_UNSUBSCRIBE_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestUnsubscribeAck ]
              context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
                case Success(a)  => a ! GossiperlProxyProtocol.UnsubscribeAck( configuration, digest.getEvent_types.asScala )
                case Failure(ex) => log.warning("Could not notify incoming unsubscribe ack. No proxy.")
              }
            case Serializer.DIGEST_FORWARDED_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestForwardedAck ]
              context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
                case Success(a)  => a ! GossiperlProxyProtocol.ForwardAck( configuration, digest.getReply_id )
                case Failure(ex) => log.warning("Could not notify incoming forward ack. No proxy.")
              }
            case _ =>
              context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
                case Success(a) => a ! GossiperlProxyProtocol.Error( configuration, new GossiperlClientException(s"Unknown digest type ${result.asInstanceOf[DeserializeResultOK].getDigestType}") )
                case Failure(_) => log.error("Could not notify unknown digest type. No proxy.")
              }
          }
        case _:DeserializeResultError =>
          context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
            case Success(a) => a ! GossiperlProxyProtocol.Error( configuration, result.asInstanceOf[DeserializeResultError].getCause )
            case Failure(_) => log.error("Could not notify deserialize error. No proxy.")
          }
        case _:DeserializeResultForward =>
          val data = result.asInstanceOf[ DeserializeResultForward ]
          context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
            case Success(a)  => a ! GossiperlProxyProtocol.Forward( configuration, data.getDigestType, data.getBinaryEnvelope, data.getEnvelopeId )
            case Failure(ex) => log.warning("Could not notify incoming forward ack. No proxy.")
          }
        case _ => log.error(s"Skipping unknown incoming message ${result.getClass.getName}")
      }
  }

  private def digestAck(digest: Digest):Unit = {
    val ack = new DigestAck()
    ack.setName( configuration.clientName )
    ack.setHeartbeat( Util.getTimestamp )
    ack.setReply_id( digest.getId )
    ack.setMembership( new util.ArrayList[DigestMember]() )
    context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}/${configuration.overlayName}-messaging/${configuration.overlayName}-transport") resolveOne() onComplete {
      case Success(a) => a ! UdpTransportProtocol.SendThrift( ack, None )
      case Failure(ex) => log.warning(s"Could not send digestAck to ${digest.getId}. Transport not found.")
    }
  }

}
