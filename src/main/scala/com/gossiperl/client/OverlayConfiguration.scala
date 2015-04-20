package com.gossiperl.client

class OverlayConfiguration( val overlayName:String,
                            val clientName:String,
                            val clientSecret:String,
                            val symmetricKey:String,
                            val overlayPort:Int,
                            val clientPort:Int,
                            val thriftWindowSize:Int = 1024 ) {
  override def toString() = {
    s"OverlayConfiguration(overlayName=${overlayName}, clientName=${clientName}, clientSecret=<protected>, symmetricKey=<protected>, overlayPort=${overlayPort}, clientPort=${clientPort}, thriftWindowSize=${thriftWindowSize})"
  }
}