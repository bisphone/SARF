package com.bisphone.sarf.implv1.tcpservice

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.bisphone.sarf.{Constant, IOCommand}
import com.bisphone.util.ByteOrder
import org.slf4j.{Logger, LoggerFactory}

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */

private[implv1] class IOCommandTransformer (
    name : String,
    byteOrder : ByteOrder,
    @deprecated debug : Boolean,
    @deprecated injectedLogger: Logger
) extends GraphStage[FlowShape[IOCommand, ByteString]] {

    implicit val order = byteOrder.javaValue

    val in = Inlet[IOCommand](s"CommandTransformer(${name}).in")
    val out = Outlet[ByteString](s"CommandTransformer(${name}).out")

    val sureDebug = injectedLogger.isDebugEnabled()

    override val shape = FlowShape(in, out)

    val loggerName = s"$name.sarf.server.io-commander"
    private val logger = LoggerFactory getLogger loggerName
    injectedLogger warn s"SEE '${loggerName}'"

    override def createLogic (inheritedAttrs: Attributes): GraphStageLogic = if (sureDebug) prodStage else debugStage

    private def addSize (bytes: ByteString): ByteString = {
        // Constant.lenOfLenField is 4 byte :)
        // Add len-field in the header !
        ByteString.newBuilder.putInt(Constant.lenOfLenField + bytes.size).append(bytes).result()
    }


    private def prodStage = new GraphStageLogic(shape) with InHandler with OutHandler {

        @inline private def eval (cmd: IOCommand) = cmd match {
            case IOCommand.Send(bytes) => push(out, addSize(bytes))
            case IOCommand.Close => completeStage()
            case IOCommand.SendAndClose(bytes) => push(out, addSize(bytes)); completeStage()
            case IOCommand.KeepGoing => push(out, ByteString.empty)
        }

        override def onPush (): Unit = eval(grab(in))

        override def onPull (): Unit = pull(in)

        setHandlers(in, out, this)
    }

    private def debugStage = new GraphStageLogic(shape) with InHandler with OutHandler {

        @inline private def transform (cmd: IOCommand) = cmd match {
            case IOCommand.Send(bytes) => push(out, addSize(bytes))
            case IOCommand.Close => completeStage()
            case IOCommand.SendAndClose(bytes) => push(out, addSize(bytes)); completeStage()
            case IOCommand.KeepGoing => push(out, ByteString.empty)
        }

        override def onPush (): Unit = {
            val a = grab(in)
            a match {
                case IOCommand.Send(bytes) =>
                    if (sureDebug) logger debug s"SEND, Bytes: ${bytes.size} + ${Constant.lenOfLenField}"
                    push(out, addSize(bytes))
                case IOCommand.Close =>
                    if (sureDebug) logger debug s"CLOSE"
                    completeStage()
                case IOCommand.SendAndClose(bytes) =>
                    push(out, addSize(bytes))
                    if (sureDebug) logger debug s"SEND & CLOSE, Bytes: ${bytes.size} + ${Constant.lenOfLenField}"
                    completeStage()
                case IOCommand.KeepGoing =>
                    if (sureDebug) logger debug s"KEEPGOING"
                    push(out, ByteString.empty)
            }
        }

        override def onPull (): Unit = pull(in)

        setHandlers(in, out, this)
    }

}

object IOCommandTransformer {

    def production (
        name: String,
        byteOrder: ByteOrder
    ): IOCommandTransformer = new IOCommandTransformer(name, byteOrder, false, LoggerFactory getLogger classOf[IOCommandTransformer])

    def testAndDebug (
        name: String,
        byteOrder: ByteOrder,
        logger: Logger
    ): IOCommandTransformer = new IOCommandTransformer(name, byteOrder, true, logger)
}