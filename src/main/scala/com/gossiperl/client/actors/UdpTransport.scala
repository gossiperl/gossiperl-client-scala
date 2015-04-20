package com.gossiperl.client.actors

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorLogging}
import akka.io.Udp.Event
import akka.io.{IO, Udp}
import akka.util.ByteString
import com.gossiperl.client.encryption.Aes256
import com.gossiperl.client.serialization.{Serializer, Serializers, CustomDigestField}
import com.gossiperl.client.thrift.DigestExit
import com.gossiperl.client.{GossiperlClientProtocol, OverlayConfiguration}

import scala.util.Try

object UdpTransportProtocol {
  case class SendThrift( digest: Serializers.Thrift )
  case class SendCustom( digestType: String, digestData: Seq[CustomDigestField] )
  case class CustomDigestSent( digestType: String, digestData: Seq[CustomDigestField] )
  case class Disconnect( digest: DigestExit )
  case object DigestExitAck extends Event
  case object Stopped
}

class UdpTransport( config: OverlayConfiguration ) extends ActorEx with ActorLogging {

  val address = new InetSocketAddress( "127.0.0.1", config.clientPort )
  val destination = new InetSocketAddress( "127.0.0.1", config.overlayPort )
  val serializer = new Serializer()
  val encryption = new Aes256( config.symmetricKey )

  import context.system
  IO(Udp) ! Udp.Bind(self, address)

  log.debug(s"Transport for ${config.overlayName} running.")

  def receive = {
    case Udp.Bound(address) =>
      context.become(ready(sender()))
  }

  def ready( socket:ActorRef ): Receive = {
    case Udp.Unbind  =>
      socket ! Udp.Unbind
    case Udp.Unbound =>
      context.stop(self)
    case UdpTransportProtocol.DigestExitAck =>
      log.debug("Received an ack of DigestExit send. Requesting Udp.Unbind which will progress to Stop.")
      self ! Udp.Unbind
    case UdpTransportProtocol.Disconnect( digestExit ) =>
      Try {
        val serialized = serializer.serialize(digestExit)
        val encrypted = encryption.encrypt(serialized)
        socket ! Udp.Send(ByteString(encrypted), destination, UdpTransportProtocol.DigestExitAck)
      } recover {
        case ex =>
          log.error(s"There was an error while requesting shutdown with $digestExit. Proceeding with stop procedure...", ex)
          self ! Udp.Unbind
      }
    case Udp.Received(data, remote) =>
      Try {
        remote.getPort match {
          case config.overlayPort =>
            val decrypted = encryption.decrypt( data.toArray )
            val result = serializer.deserialize( decrypted )
            !:( s"/user/${Supervisor.supervisorName}/${config.overlayName}/messaging", MessagingProtocol.IncomingData( result ) )
          case _ => log.debug(s"Ignoring data incoming from ${remote.getPort}")
        }
      } recover {
        case ex => !:( s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Error( config, ex.getMessage, ex ) ) )
      }
    case UdpTransportProtocol.SendThrift( digest ) =>
      Try {
        val serialized = serializer.serialize( digest )
        val encrypted  = encryption.encrypt( serialized )
        socket ! Udp.Send( ByteString( encrypted ), destination )
      } recover {
        case ex => !:( s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Error( config, s"Error while sending digest $digest.", ex ) ) )
      }
    case UdpTransportProtocol.SendCustom(digestType, fields) =>
      import scala.collection.JavaConversions._
      Try {
        val serialized = serializer.serializeArbitrary( digestType, fields.toList, config.thriftWindowSize )
        val encrypted  = encryption.encrypt( serialized )
        socket ! Udp.Send( ByteString( encrypted ), destination )
        sender ! UdpTransportProtocol.CustomDigestSent( digestType, fields )
      } recover {
        case ex => !:( s"/user/${Supervisor.supervisorName}", SupervisorProtocol.ForwardEvent( config, GossiperlClientProtocol.Error( config, s"Error while sending custom digest $digestType.", ex ) ) )
      }
  }

  override def postStop():Unit = {
    log.debug("Transport is now stopped. Confirming to overlay worker...")
    !:(s"/user/${Supervisor.supervisorName}/${config.overlayName}", UdpTransportProtocol.Stopped)
  }

}
