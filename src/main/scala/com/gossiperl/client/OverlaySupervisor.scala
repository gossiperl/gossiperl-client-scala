package com.gossiperl.client

import akka.actor.Actor

class OverlayConfiguration( val overlayName:String,
                            val clientName:String,
                            val clientSecret:String,
                            val symmetricKey:String,
                            val overlayPort:Int,
                            val clientPort:Int )

//class OverlaySupervisor extends Actor {

//}
