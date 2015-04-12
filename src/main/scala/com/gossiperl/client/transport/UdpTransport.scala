package com.gossiperl.client.transport

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorLogging, Actor}
import akka.io.Udp.Event
import akka.io.{Udp, IO}
import akka.util.{ByteString, Timeout}
import com.gossiperl.client.thrift.DigestExit
import com.gossiperl.client.{GossiperlProxyProtocol, Supervisor, OverlayConfiguration}
import com.gossiperl.client.encryption.Aes256
import com.gossiperl.client.serialization.{DeserializeResult, Serializer, CustomDigestField}
import org.apache.thrift.{TFieldIdEnum, TBase}
import UdpTransportProtocol._
import scala.collection.JavaConversions._
import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}
import concurrent.duration._

object UdpTransportProtocol {
  case class SendThrift( digest: TBase[_ <: TBase[_,_], _ <: TFieldIdEnum], p: Option[Promise[ActorRef]] )
  case class SendCustom( digestType: String, digestData: Seq[CustomDigestField] )
  case class IncomingData( deserializeResult: DeserializeResult )
  case class DigestExitAck( p: Promise[ActorRef] ) extends Event
}

class UdpTransport( val configuration: OverlayConfiguration ) extends Actor with ActorLogging {

  val address = new InetSocketAddress( "127.0.0.1", configuration.clientPort )
  val destination = new InetSocketAddress( "127.0.0.1", configuration.overlayPort )
  val serializer = new Serializer()
  val encryption = new Aes256( configuration.symmetricKey )

  private val digestExitClassName = new DigestExit().getClass.getName

  import context.system
  IO(Udp) ! Udp.Bind(self, address)

  implicit val timeout = Timeout(1 seconds)
  import scala.concurrent.ExecutionContext.Implicits.global

  log.debug(s"Messaging transport for overlay ${configuration.overlayName} is running.")

  def receive = {
    case Udp.Bound(address) =>
      context.become( ready( sender() ) )
  }

  def ready(socket: ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      Try {
        val decrypted = encryption.decrypt( data.toArray )
        val result = serializer.deserialize( decrypted )
        context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}/${configuration.overlayName}-messaging") resolveOne() onComplete {
          case Success(a)  => a ! IncomingData( result )
          case Failure(ex) => log.warning("Could not offer incoming data. Messaging not found.")
        }
      } recover {
        case ex => log.error("Error while processing incoming data.", ex)
      }
    case Udp.Unbind  => socket ! Udp.Unbind
    case Udp.Unbound => context.stop(self)
    case DigestExitAck( p ) =>
      log.debug("Received an ack of DigestExit send. Requesting Udp.Unbind which will progress to Stop.")
      self ! Udp.Unbind
      p.success( self )
    case SendThrift( digest, op ) =>
      Try {
        val serialized = serializer.serialize( digest )
        val encrypted  = encryption.encrypt( serialized )
        ( digest.getClass.getName, op ) match {
          case ( digestExitClassName, Some(p) ) =>
            log.debug("Received DigestExit, sending the digest an awaiting for an ack...")
            socket ! Udp.Send( ByteString( encrypted ), destination, DigestExitAck( p ) )
          case (_, _) => socket ! Udp.Send( ByteString( encrypted ), destination )
        }
      } recover {
        case ex =>
          log.error(s"There was an error while sending digest $digest.", ex)
          context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
            case Success(a) => a ! GossiperlProxyProtocol.Error( configuration, ex )
            case Failure(_) => log.error("Could not notify send error. No proxy.")
          }
      }
    case SendCustom(digestType, fields) =>
      Try {
        val serialized = serializer.serializeArbitrary( digestType, fields.toList, configuration.thriftWindowSize )
        val encrypted  = encryption.encrypt( serialized )
        socket ! Udp.Send( ByteString( encrypted ), destination )
      } recover {
        case ex =>
          log.error(s"There was an error while sending custom digest $digestType.", ex)
          context.system.actorSelection(s"/user/${Supervisor.actorName}/${configuration.overlayName}-proxy") resolveOne() onComplete {
            case Success(a) => a ! GossiperlProxyProtocol.Error( configuration, ex )
            case Failure(_) => log.error("Could not notify custom send error. No proxy.")
          }
      }
  }

}
