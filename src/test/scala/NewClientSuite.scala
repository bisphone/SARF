import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.bisphone.sarf._
import com.bisphone.sarf.implv1.util.{ StreamConfig, TCPConfigForClient, TCPConfigForServer }
import util.{ BaseSuite, SayProtocol, Server }
import com.bisphone.std._
import util.SayProtocol._
import com.bisphone.sarf.implv2.tcpclient._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.control.NonFatal

class NewClientSuite extends TestKit(ActorSystem()) with BaseSuite with NewClientSuite.Helpers {

    protected val logger = LoggerFactory getLogger "TestSuite"

    implicit val ec = ExecutionContext.global

    object First extends Conn("first", "localhost", 10010)

    object Second extends Conn("second", "localhost", 10020)


    it must "send & receive commands" in {

        val name = "Reza"

        implicit val timeout: FiniteDuration = 10 seconds

        var firstServer = up(First)
        var secondServer = up(Second)

        logSeperator("Servers are Up & Running")

        def new1 = {
            val conns =
                First.conn(timeout) :: Second.conn(timeout) :: Nil

            val conf =
                Director.Config(
                    connections = conns,
                    minumumAdorableConnections = 1,
                    maximumTroubleTime = 20 seconds,
                    maxRetry = 3,
                    retryDelay = 10 seconds,
                    initTimeout = 20 seconds,
                    requestTimeout = 5 seconds
                )

            TCPClient("client", conf, SayProtocol.writer, SayProtocol.reader, system, ec)
        }

        def new2 = {
            val conns =
                First.conn(timeout) :: Second.conn(timeout) :: Nil

            val conf =
                NewDirector.Config(
                    connections = conns,
                    greenStateLimit = 2,
                    endureRedState = Duration.Inf,
                    initTimeout = 10 seconds,
                    requestTimeout = 5 seconds,
                    reconnectingDelay = 10 seconds
                )

            NewClient("client", conf, SayProtocol.writer, SayProtocol.reader, system, ec)
        }

        implicit val client = new2

        logSeperator("Client is UP !?")


        getRight(SayHello(name)).name should startWith(s"'${name }' from")

        getLeft(SayBye(name)).value should startWith(s"Ops from")

        getLeft(SayBye(name)).value should startWith(s"Ops from")

        logSeperator("Client is Working !")

        try {

            logSeperator("FirstServer is going down ...")

            down(firstServer)

            logSeperator("FirstServer is down! try messages ...")

            // for (i <- 1 to 200) getRight(SayHello(name)).name should startWith(s"'${name }' from")
            for (i <- 1 to 200) justLog(SayHello(name))

            logSeperator("Starting First Server ... ")

            firstServer = up(First)

            restFor(7 seconds)

            logSeperator("First Server ... Up ?! Going to send messages ...")

            // for (i <- 1 to 20) getRight(SayHello(name)).name should startWith(s"'${name }' from")
            for (i <- 1 to 20) justLog(SayHello(name))

            logSeperator("End of Messages; Second Server Shutdown ... ")

            down(secondServer)

            logSeperator("Second Server Shutdown! Going to send messages ...")

            // for (i <- 1 to 20) getRight(SayHello(name)).name should startWith(s"'${name }' from")
            for (i <- 1 to 20) justLog(SayHello(name))

            logSeperator("First Server Shutdown ...")

            down(firstServer)

            logSeperator("First Server Shutdown! Going to send messages ...")

            for (i <- 1 to 20) justLog(SayHello(name))

            logSeperator("End of messages! Going to Start both servers ...")

            firstServer = up(First)
            secondServer = up(Second)


            restFor(10 seconds)

            logSeperator("Servers are Up and Running ?! Going to Send Messages")

            for (i <- 1 to 20) getRight(SayHello(name)).name should startWith(s"'${name }' from")

            logSeperator("End Of Messages!")

        } catch {
            case NonFatal(cause) =>
                logger info s"HealthCheck: ${client.healthcheck()}"
                throw cause
        }
    }

}

object NewClientSuite {

    trait Helpers { self: NewClientSuite =>

        class Conn (
            val name: String,
            val host: String,
            val port: Int
        ) {
            def server: TCPConfigForServer = TCPConfigForServer(host, port, backlog)

            def client (timeout: FiniteDuration): TCPConfigForClient = TCPConfigForClient(host, port, timeout)

            def conn (timeout: FiniteDuration): Connection.Config =
                Connection.Config(s"client-${name }", client(timeout), stream)

            def backlog: Int = 10

            final def stream: StreamConfig = StreamConfig(2000, order, 10)

            override def toString: String = s"(${name }, ${host }:${port })"
        }

        def up (
            name: String,
            config: TCPConfigForServer,
            stream: StreamConfig
        )
            (
                implicit timeout: FiniteDuration
            ): TCPServiceRef = {
            Await.result(Server.tcp(name, config, stream), timeout)
        }

        def up (conn: Conn)
            (
                implicit timeout: FiniteDuration
            ): (Conn, TCPServiceRef) = {
            logger debug s"Start Server: ${conn }"
            (conn, up(conn.name, conn.server, conn.stream))
        }

        def down (ref: (Conn, TCPServiceRef))
            (
                implicit timeout: FiniteDuration
            ): Unit = {
            logger info s"Stop Server: ${ref._1 }"
            Await.result(ref._2.shutdown, timeout)

        }


        def justLog[Fn <: Func] (
            fn: Fn
        )
            (
                implicit
                fnKey: TypeKey[Fn],
                fnWriter: Writer[Fn, Tracked, Untracked],
                errKey: TypeKey[Fn#Error],
                errReader: Reader[Fn#Error, Tracked],
                outKey: TypeKey[Fn#Result],
                outReader: Reader[Fn#Result, Tracked],
                pos: org.scalactic.source.Position,
                timeout: FiniteDuration,
                client: TCPClientRef[Tracked, Untracked]
            ) = try Await.result(client(fn).asFuture, timeout) match {
            case StdRight(value) =>
                logger debug s"Right: ${value }"
            case StdLeft(value) =>
                logger debug s"Left: ${value }"
        } catch {
            case NonFatal(cause) =>
                logger debug s"Failure: ${cause.getMessage }"

        }

        def getRight[Fn <: Func] (
            fn: Fn
        )
            (
                implicit
                fnKey: TypeKey[Fn],
                fnWriter: Writer[Fn, Tracked, Untracked],
                errKey: TypeKey[Fn#Error],
                errReader: Reader[Fn#Error, Tracked],
                outKey: TypeKey[Fn#Result],
                outReader: Reader[Fn#Result, Tracked],
                pos: org.scalactic.source.Position,
                timeout: FiniteDuration,
                client: TCPClientRef[Tracked, Untracked]
            ) = try Await.result(client(fn).asFuture, timeout) match {
            case StdRight(value) =>
                logger debug s"Right: ${value }"
                value
            case StdLeft(value) =>
                logger debug s"Left: ${value }"
                fail(s"Left: ${value }")
        } catch {
            case NonFatal(cause) =>
                logger debug s"Failure: ${cause.getMessage }"
                throw cause
        }

        def getLeft[Fn <: Func] (
            fn: Fn
        )
            (
                implicit
                fnKey: TypeKey[Fn],
                fnWriter: Writer[Fn, Tracked, Untracked],
                errKey: TypeKey[Fn#Error],
                errReader: Reader[Fn#Error, Tracked],
                outKey: TypeKey[Fn#Result],
                outReader: Reader[Fn#Result, Tracked],
                pos: org.scalactic.source.Position,
                timeout: FiniteDuration,
                client: TCPClientRef[Tracked, Untracked]
            ) = try Await.result(client(fn).asFuture, timeout) match {
            case StdLeft(value) =>
                logger debug s"Left: ${value }"
                value
            case StdRight(value) =>
                logger debug s"Right: ${value }"
                fail(s"Right : ${value }")
        } catch {
            case NonFatal(cause) =>
                logger debug s"Failure: ${cause.getMessage}"
                throw cause
        }

        def restFor (duration: FiniteDuration) = {
            logger info s"Rest for ${duration }"
            Thread sleep duration.toMillis
        }

        def logSeperator(title: String) = {
            val const = 120
            val len = const - title.length
            logger info s"### ${title} ${"#" * len}\n"
        }

    }

}