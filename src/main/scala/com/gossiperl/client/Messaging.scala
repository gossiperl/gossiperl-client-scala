package com.gossiperl.client

import java.util

import akka.actor.ActorLogging
import akka.util.Timeout
import com.gossiperl.client.actors.ActorEx
import com.gossiperl.client.exceptions.GossiperlClientException
import com.gossiperl.client.serialization.{Serializer, DeserializeResultForward, DeserializeResultError, DeserializeResultOK}
import com.gossiperl.client.thrift._
import com.gossiperl.client.transport.UdpTransportProtocol
import scala.collection.JavaConverters._

class Messaging(val configuration: OverlayConfiguration) extends ActorEx with ActorLogging {

  import scala.concurrent.duration._
  implicit val timeout = Timeout(1 seconds)

  log.debug(s"Messaging for overlay ${configuration.overlayName} is running.")

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
              !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}/client-state", FSMProtocol.AckReceived)
            case Serializer.DIGEST_EVENT =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestEvent ]
              !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.Event( configuration, digest.getEvent_type, digest.getEvent_object, digest.getHeartbeat ))
            case Serializer.DIGEST_SUBSCRIBE_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestSubscribeAck ]
              !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.SubscribeAck( configuration, digest.getEvent_types.asScala ))
            case Serializer.DIGEST_UNSUBSCRIBE_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestUnsubscribeAck ]
              !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.UnsubscribeAck( configuration, digest.getEvent_types.asScala ))
            case Serializer.DIGEST_FORWARDED_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestForwardedAck ]
              !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.ForwardAck( configuration, digest.getReply_id ))
            case _ =>
              !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.Error( configuration, new GossiperlClientException(s"Unknown digest type ${result.asInstanceOf[DeserializeResultOK].getDigestType}") ))
          }
        case _:DeserializeResultError =>
          !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.Error( configuration, result.asInstanceOf[DeserializeResultError].getCause ))
        case _:DeserializeResultForward =>
          val data = result.asInstanceOf[ DeserializeResultForward ]
          !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy", GossiperlProxyProtocol.Forward( configuration, data.getDigestType, data.getBinaryEnvelope, data.getEnvelopeId ))
        case _ => log.error(s"Skipping unknown incoming message ${result.getClass.getName}")
      }
  }

  private def digestAck(digest: Digest):Unit = {
    val ack = new DigestAck()
    ack.setName( configuration.clientName )
    ack.setHeartbeat( Util.getTimestamp )
    ack.setReply_id( digest.getId )
    ack.setMembership( new util.ArrayList[DigestMember]() )
    !:(s"/user/${Supervisor.actorName}/${configuration.overlayName}/transport", UdpTransportProtocol.SendThrift( ack ))
  }

}
