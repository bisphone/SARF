import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.bisphone.sarf.{ Func, Reader, TypeKey, Writer }
import com.bisphone.sarf.implv1.util.{ StreamConfig, TCPConfigForClient, TCPConfigForServer }
import util.{ BaseSuite, SayProtocol, Server }
import com.bisphone.std._
import util.SayProtocol._
import com.bisphone.sarf.implv2.tcpclient._

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class NewClientSuite extends TestKit(ActorSystem()) with BaseSuite {

    implicit val ec = ExecutionContext.global

    val stream = StreamConfig(2000, order, 10)

    val first = "first"
    val second = "second"

    val servers = Map(
        first -> TCPConfigForServer("localhost", 10010, 10),
        second -> TCPConfigForServer("localhost", 10020, 10)
    )

    val allServers = Future sequence servers.map {
        case (name, conf) => Server.tcp(name, conf, stream)
    }

    Await.result(allServers, 20 seconds)

    println(" > Init Servers ... Done!")

    val conns =
        Connection.Config(s"client-${first}", TCPConfigForClient("localhost", 10010, 10 seconds), stream) ::
            // Connection.Config(s"client-${second}", TCPConfigForClient("localhost", 10020, 10 seconds), stream) ::
            Nil

    val conf =
        Director.Config(conns, 1, 20 seconds, 3, 20 seconds, 20 seconds, 2 seconds)

    val client =
        TCPClient("client", conf, SayProtocol.writer, SayProtocol.reader, system, ec)

    println(" > Init Clinet ... Done!")


    def getRight[Fn <: Func, T, U](
        fn: Fn
    )(
        implicit
        fnKey: TypeKey[Fn],
        fnWriter: Writer[Fn, Tracked, Untracked],
        errKey: TypeKey[Fn#Error],
        errReader: Reader[Fn#Error, Tracked],
        outKey: TypeKey[Fn#Result],
        outReader: Reader[Fn#Result, Tracked],
        pos: org.scalactic.source.Position,
        timeout: FiniteDuration
    ) = Await.result(client(fn).asFuture, timeout) match {
        case StdRight(value) => value
        case StdLeft(value) => fail(s"Left: ${value}")
    }

    def getLeft[Fn <: Func](
        fn: Fn
    )(
        implicit
        fnKey: TypeKey[Fn],
        fnWriter: Writer[Fn, Tracked, Untracked],
        errKey: TypeKey[Fn#Error],
        errReader: Reader[Fn#Error, Tracked],
        outKey: TypeKey[Fn#Result],
        outReader: Reader[Fn#Result, Tracked],
        pos: org.scalactic.source.Position,
        timeout: FiniteDuration
    ) = Await.result(client(fn).asFuture, timeout) match {
        case StdLeft(value) => value
        case StdRight(value) => fail(s"Right : ${value}")
    }

    "SayHello" must "send & receive commands" in {

        val name = "Reza"

        implicit val timeout = 10 seconds

        getRight(SayHello(name)).name should startWith(s"'${name}' from")

        getLeft(SayBye(name)).value should  startWith(s"Ops from")
    }

}
