package com.gossiperl.client.serialization

import org.apache.thrift.{TFieldIdEnum, TBase}

object Serializers {
  type Thrift = TBase[_ <: TBase[_,_], _ <: TFieldIdEnum]
}
