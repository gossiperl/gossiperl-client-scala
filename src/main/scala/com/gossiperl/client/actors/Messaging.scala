package com.gossiperl.client.actors

import java.util.ArrayList

import akka.actor.ActorLogging
import com.gossiperl.client.exceptions.GossiperlClientException
import com.gossiperl.client.serialization._
import com.gossiperl.client.thrift._
import com.gossiperl.client.{GossiperlClientProtocol, Util, GossiperlClient, OverlayConfiguration}

object MessagingProtocol {

  case object AckReceived
  case object Stopped
  case class IncomingData( deserializeResult: DeserializeResult )

}

class Messaging( config: OverlayConfiguration ) extends ActorEx with ActorLogging {

  log.debug(s"Messaging for ${config.overlayName} running.")

  def receive = {
    case OverlayWorkerProtocol.Disconnect =>
      context.system.stop( self )
    case MessagingProtocol.IncomingData(result) =>
      import scala.collection.JavaConverters._
      result match {
        case _:DeserializeResultOK =>
          result.asInstanceOf[DeserializeResultOK].getDigestType match {
            case Serializer.DIGEST =>
              digestAck( result.asInstanceOf[DeserializeResultOK].getDigest.asInstanceOf[ Digest ] )
            case Serializer.DIGEST_ACK =>
              !:(s"/user/${Supervisor.supervisorName}/${config.overlayName}/state", MessagingProtocol.AckReceived)
            case Serializer.DIGEST_EVENT =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestEvent ]
              !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Event( config, digest.getEvent_type, digest.getEvent_object, digest.getHeartbeat ) ))
            case Serializer.DIGEST_SUBSCRIBE_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestSubscribeAck ]
              !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.SubscribeAck( config, digest.getEvent_types.asScala ) ))
            case Serializer.DIGEST_UNSUBSCRIBE_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestUnsubscribeAck ]
              !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.UnsubscribeAck( config, digest.getEvent_types.asScala ) ))
            case Serializer.DIGEST_FORWARDED_ACK =>
              val digest = result.asInstanceOf[ DeserializeResultOK ].getDigest.asInstanceOf[ DigestForwardedAck ]
              !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.ForwardAck( config, digest.getReply_id ) ))
            case _ =>
              val ex = new GossiperlClientException(s"Unknown digest type ${result.asInstanceOf[DeserializeResultOK].getDigestType}")
              !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Error( config, ex.getMessage, ex ) ))
          }
        case _:DeserializeResultError =>
          val ex = result.asInstanceOf[DeserializeResultError].getCause
          !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Error( config, ex.getMessage, ex ) ))
        case _:DeserializeResultForward =>
          val data = result.asInstanceOf[ DeserializeResultForward ]
          !:(s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Forward( config, data.getDigestType, data.getBinaryEnvelope, data.getEnvelopeId ) ))
        case _ => log.error(s"Skipping unknown incoming message ${result.getClass.getName}")
      }
  }

  override def postStop():Unit = {
    log.debug("Messaging is now stopped. Confirming to overlay worker...")
    !:(s"/user/${Supervisor.supervisorName}/${config.overlayName}", MessagingProtocol.Stopped)
  }

  private def digestAck(digest: Digest):Unit = {
    val ack = new DigestAck()
    ack.setName( config.clientName )
    ack.setHeartbeat( Util.getTimestamp )
    ack.setReply_id( digest.getId )
    ack.setMembership( new ArrayList[DigestMember]() )
    !:(s"/user/${Supervisor.supervisorName}/${config.overlayName}/transport", UdpTransportProtocol.SendThrift( ack ))
  }

}
