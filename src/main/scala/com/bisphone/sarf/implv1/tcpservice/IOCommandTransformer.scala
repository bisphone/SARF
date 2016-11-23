package com.bisphone.sarf.implv1.tcpservice

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.bisphone.sarf.{Constant, IOCommand}
import com.bisphone.util.ByteOrder
import org.slf4j.Logger

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */

private[implv1] class IOCommandTransformer (
   name: String,
   byteOrder: ByteOrder,
   debug: Boolean,
   logger: Logger
) extends GraphStage[FlowShape[IOCommand, ByteString]] {

   implicit val order = byteOrder.javaValue

   val in = Inlet[IOCommand](s"CommandTransformer(${name}).in")
   val out = Outlet[ByteString](s"CommandTransformer(${name}).out")

   val sureDebug = debug && logger.isDebugEnabled()

   override val shape = FlowShape(in, out)

   override def createLogic (inheritedAttrs: Attributes): GraphStageLogic = if (sureDebug) prodStage else debugStage

   private def addSize (bytes: ByteString): ByteString = {
      // Constant.lenOfLenField is 4 byte :)
      // Add len-field in the header !
      ByteString.newBuilder.putInt(Constant.lenOfLenField + bytes.size).append(bytes).result()
   }

   private def prodStage = new GraphStageLogic(shape) with InHandler with OutHandler {

      override def onPush (): Unit = grab(in) match {
         case IOCommand.Send(bytes) => push(out, addSize(bytes))
         case IOCommand.Close => completeStage()
         case IOCommand.SendAndClose(bytes) => push(out, addSize(bytes)); completeStage()
         case IOCommand.KeepGoing => push(out, ByteString.empty)
      }

      override def onPull (): Unit = pull(in)

      setHandlers(in, out, this)
   }

   private def debugStage = new GraphStageLogic(shape) with InHandler with OutHandler {

      override def onPush (): Unit = grab(in) match {
         case IOCommand.Send(bytes) =>
            if (sureDebug) logger debug s"{'subject': 'CommandTransformer.SEND(${bytes.size} + ${Constant.lenOfLenField} byets)'}"
            push(out, addSize(bytes))
         case IOCommand.Close =>
            if (sureDebug) logger debug s"{'subject': 'CommandTransformer.CLOSED'}"
            completeStage()
         case IOCommand.SendAndClose(bytes) =>
            push(out, addSize(bytes))
            if (sureDebug) logger debug s"{'subject': 'CommandTransformer.SEND(${bytes.size} + ${Constant.lenOfLenField} byets)&CLOSE'}"
            completeStage()
         case IOCommand.KeepGoing =>
            if (sureDebug) logger debug s"{'subject': 'CommandTransformer.KEEPGOING'}"
            push(out, ByteString.empty)
      }

      override def onPull (): Unit = pull(in)

      setHandlers(in, out, this)
   }

}

