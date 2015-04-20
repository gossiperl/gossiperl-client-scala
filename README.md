# Scala Gossiperl client

Scala [gossiperl](http://gossiperl.com) client library.

## Installation

To build from sources an installation of Scala and SBT is necessary.

    git clone https://github.com/gossiperl/gossiperl-client-scala.git
    cd gossiperl-client-scala/
    git checkout $(git tag -l | sort -r | head -n 1)
    sbt package

Deploy in your local Maven or alike. The library depends on [gossiperl-core-jvm](https://github.com/gossiperl/gossiperl-core-jvm).

## Connecting to an overlay

    import com.gossiperl.client.GossiperlClient
    import com.gossiperl.client.OverlayConfiguration

    class YourConnector extends GossiperlClient with LazyLogging {
      override mplicit val config = new OverlayConfiguration(
        overlayName = "gossiper_overlay_remote",
        clientName = "scala-client",
        symmetricKey = "v3JElaRswYgxOt4b",
        clientSecret = "scala-client-secret",
        overlayPort = 6666,
        clientPort = 54321
      )
      
      connect()
    }

Listening to events:
    
    import com.gossiperl.client.{GossiperlClient, GossiperlClientProtocol}
    import com.gossiperl.client.OverlayConfiguration
    
    class YourConnector extends GossiperlClient with LazyLogging {
      override mplicit val config = new OverlayConfiguration(
        overlayName = "gossiper_overlay_remote",
        clientName = "scala-client",
        symmetricKey = "v3JElaRswYgxOt4b",
        clientSecret = "scala-client-secret",
        overlayPort = 6666,
        clientPort = 54321
      )
      
      def event = {
        case GossiperlClientProtocol.Accepted( config ) => // client accepted by the supervisor
        case GossiperlClientProtocol.Connected( config ) => // client connected to the overlay
        case GossiperlClientProtocol.Disconnected( config ) => // client disconnected from an overlay / connection lost
        case GossiperlClientProtocol.Stopped( config ) => // shutdown procedure for the client completed
        case GossiperlClientProtocol.Error( config, message, reason ) => // there was an error, check reason
        case GossiperlClientProtocol.Event( configuration, eventType, member, heartbeat ) => one of the internal gossiperl events notification
        case GossiperlClientProtocol.SubscribeAck( configuration, eventTypes ) => // acknowledgement of subscribing to a number of events
        case GossiperlClientProtocol.UnsubscribeAck( configuration, eventTypes ) => // acknowledgement of unsubscribing from a number of events
        case GossiperlClientProtocol.ForwardAck( configuration, replyId ) => // acknowledgement of forward message
        case GossiperlClientProtocol.Forward( configuration, digestType, binaryEnvelope, envelopeId ) => // received arbitrary custom digest
      }
      
      connect()
    }

## Subscribing / unsubscribing

Subscribing:

    subscribe( events: Seq[String] ):Future[Seq[String]]
    
If the request was successfully accepted, the future will complete with the same list of event types. Once the subscriptions are confirmed by the overlay, a `GossiperlClientProtocol.SubscribeAck` will be delivered to the client.

Unsubscribing:

    unsubscribe( events: Seq[String] ):Future[Seq[String]]

If the request was successfully accepted, the future will complete with the same list of event types. Once the unsubscription are confirmed by the overlay, a `GossiperlClientProtocol.UnsubscribeAck` will be delivered to the client.

## Disconnecting from an overlay

    disconnect

## Additional operations

### Checking current client state

    currentState: Future[com.gossiperl.client.FSMState.ClientState]

### Get the list of current subscriptions

    subscriptions: Future[Seq[String]]

### Sending arbitrary digests

    import com.gossiperl.client.serialization.CustomDigestField;
    
    String overlayName = "gossiper_overlay_remote";
    val digestData = Seq[CustomDigestField](
                            new CustomDigestField("field_name", "some value for the field", 1),
                            new CustomDigestField("integer_field", 1234L, 2) )
    send( "customDigestType", digestData ): Future[Tuple2[String, Seq[CustomDigestField]]]

If send is successful, the future will respond with the input.

### Reading custom digests

To read a custom digest, assuming that this is the binary envelope data received via `forwarded` listener, it can be read in the following manner:

    proxy.read("expectedDigestType", binaryData, digestInfo) match {
      case Success(result) => result match {
        case customResult:DeserializeResultCustomOK => // custom data avaialable
        case anyOther => logger.error(s"There was an error while processing custom read: $anyOther")
      } 
    }

Where binary data `binaryEnvelope` returned by `GossiperlClientProtocol.Forward`.

## Running tests

    sbt test

Tests assume an overlay with the details specified in the `test/com/gossiperl/client/ProcessTest.scala` running.

## License

The MIT License (MIT)

Copyright (c) 2015 Radoslaw Gruchalski <radek@gruchalski.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
