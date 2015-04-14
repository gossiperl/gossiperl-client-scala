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
      val config = new OverlayConfiguration(
        overlayName = "gossiper_overlay_remote",
        clientName = "scala-client",
        symmetricKey = "v3JElaRswYgxOt4b",
        clientSecret = "scala-client-secret",
        overlayPort = 6666,
        clientPort = 54321
      )
      withOverlay( config ) {
        case Some(proxy) => // an instance of GossiperlProxy
        case None => logger.error(s"Could not load proxy for $config, supervisor isn't running.")
      }
    }

To use a custom listener:

    withOverlay( config ) {
      case Some(proxy) =>
        proxy.event {
          case GossiperlProxyProtocol...( data ) =>
          case GossiperlProxyProtocol...( data ) =>
        }
      case None => logger.error(s"Could not load proxy for $config, supervisor isn't running.")
    }

A client may be connected to multiple overlays.

## Subscribing / unsubscribing

Subscribing:

    proxy.subscribe( events: Seq[String ):Future[ Option[ Seq[ String ] ] ]

Unsubscribing:

    proxy.unsubscribe( events: Seq[String ):Future[ Option[ Seq[ String ] ] ]

## Disconnecting from an overlay

    proxy.disconnect

## Additional operations

### Checking current client state

    proxy.currentState: Future[Option[com.gossiperl.client.FSMState.ClientState]]

### Get the list of current subscriptions

    proxy.subscriptions: Future[Option[Seq[String]]]

### Sending arbitrary digests

    import com.gossiperl.client.serialization.CustomDigestField;
    
    String overlayName = "gossiper_overlay_remote";
    val digestData = Seq[CustomDigestField](
                            new CustomDigestField("field_name", "some value for the field", 1),
                            new CustomDigestField("integer_field", 1234, 2) )
    proxy.send( "customDigestType", digestData ): Future[Array[Byte]]

If send is successful, the future will return an encrypted digest.

### Reading custom digests

To read a custom digest, assuming that this is the binary envelope data received via `forwarded` listener, it can be read in the following manner:

    Try { proxy.read("expectedDigestType", binaryData, digestInfo) }

Where binary data is `Array[Byte]` buffer of the notification and `digestInfo` has the same format as `digestData` as in the example above.

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