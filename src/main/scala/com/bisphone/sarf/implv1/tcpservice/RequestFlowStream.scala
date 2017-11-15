package com.bisphone.sarf.implv1.tcpservice

import akka.actor.{ActorRef, Props}
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Source}
import akka.stream.stage.GraphStage
import akka.util.ByteString
import com.bisphone.akkastream.ByteStreamSlicer
import com.bisphone.sarf.{Constant, IOCommand}
import com.bisphone.sarf.implv1.util.StreamConfig
import com.bisphone.util.ByteOrder
import org.slf4j.Logger

import scala.concurrent.Future

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
private[implv1] object RequestFlowStream {

   private def slicer (
      name: String,
      byteOrder: ByteOrder,
      maxSize: Int
   ): GraphStage[FlowShape[ByteString, ByteString]] =
      ByteStreamSlicer(name, Constant.lenOfLenField, maxSize, byteOrder)

   def apply (
      name: String,
      conf: StreamConfig,
      connectionAgent: Props,
      logger: Logger,
      debug: Boolean
   )(
      fn: ByteString => Future[IOCommand]
   ): Flow[ByteString, ByteString, ActorRef] = {

      val maxSliceSize = conf.maxSliceSize
      val byteOrder = conf.byteOrder
      val concurrencyPerConnection = conf.concurrencyPerConnection

      val sureDebug = debug && logger.isDebugEnabled()

      def logBytes (subject: String): ByteString => ByteString = bytes => {
         val arr = bytes.toArray
         val iter = arr.iterator

         val str = if (arr.size > 1) {
            val buf = new StringBuilder
            buf.append(iter.next() & 0xFF)
            while (iter.hasNext) {
               buf.append(", ").append(iter.next() & 0xFF)
            }
            buf.toString()
         } else if (arr.size == 1) (iter.next() & 0xFF).toString else ""

         logger trace s"${subject}, Bytes(${bytes.size}): ${str}"

         bytes
      }

      val sourceOfAgent = Source.actorPublisher[IOCommand](connectionAgent)

      val graph = GraphDSL.create(sourceOfAgent) { implicit builder => source =>

         import GraphDSL.Implicits._

         val mergeStage = builder add Merge[IOCommand](2, eagerComplete = true)

         source ~> mergeStage

         val slicerStage: FlowShape[ByteString, ByteString] = {
            val a = Flow[ByteString] via slicer(s"${name}", byteOrder, maxSliceSize)
            val b = if (sureDebug) a map logBytes(s"${name}.sarf.server.ByteInputStream") else a
            builder add b
         }

         slicerStage
            .map(_.drop(Constant.lenOfLenField)) // Remove len-filed from stream
            .mapAsync(concurrencyPerConnection)(fn) ~> mergeStage

         // IOCommandTransformer will add len-field when it's needed
         val merged = mergeStage.out.via(new IOCommandTransformer(name, conf.byteOrder, debug, logger))

         val result = if (sureDebug) merged map logBytes(s"${name}.sarf.server.ByteOutputStream") else merged

         FlowShape(slicerStage.in, result.outlet)
      }

      Flow fromGraph graph
   }

}
