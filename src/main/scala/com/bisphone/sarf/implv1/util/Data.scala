package com.bisphone.sarf.implv1.util

import com.bisphone.util.ByteOrder

import scala.concurrent.duration.FiniteDuration

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
case class TCPConfigForServer(
  host: String,
  port: Int,
  backlog: Int
)

case class StreamConfig(
  maxSliceSize: Int,
  byteOrder: ByteOrder,
  concurrencyPerConnection: Int
)


case class TCPConfigForClient(
  host: String,
  port: Int,
  connectingTimeout: FiniteDuration
)