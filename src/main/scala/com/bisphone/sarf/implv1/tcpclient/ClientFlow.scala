package com.bisphone.sarf.implv1.tcpclient

import akka.actor.{ActorRef, Props}
import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.bisphone.akkastream.ByteStreamSlicer
import com.bisphone.sarf.Constant
import com.bisphone.sarf.implv1.util.StreamConfig
import com.bisphone.util.ByteOrder
import org.slf4j.Logger

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
object ClientFlow {

    private def slicer (
        name: String,
        byteOrder: ByteOrder,
        maxSize: Int
    ): GraphStage[FlowShape[ByteString, ByteString]] =
        ByteStreamSlicer(name, Constant.lenOfLenField, maxSize, byteOrder)

    def apply (
        name: String,
        conf: StreamConfig,
        publisher: Props,
        consumer: Props,
        logger: Logger,
        debug: Boolean
    ): Flow[ByteString, ByteString, (ActorRef, ActorRef)] = {

        val maxSliceSize = conf.maxSliceSize
        val byteOrder = conf.byteOrder

        val source = {
            val tmp = Source.actorPublisher[ByteString](publisher).map { bytes =>
                // Constant.lenOfLenField is 4
                // Add len-field to the header
                ByteString.newBuilder.putInt(Constant.lenOfLenField + bytes.size)(byteOrder.javaValue).append(bytes).result()
            }

            tmp
        }
        val slices = {
            Flow.fromGraph(slicer(name, byteOrder, maxSliceSize))
        }.map(_.drop(Constant.lenOfLenField)) // Remove len-filed from stream

        val sink = slices.toMat(Sink.actorSubscriber[ByteString](consumer))(Keep.right)

        Flow.fromSinkAndSourceMat(sink, source)(Keep.both)
    }

}
