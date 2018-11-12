
import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.ByteString
import com.bisphone.sarf._
import com.bisphone.sarf.implv1.{ Service, StatCollector, TCPClient, TCPServer }
import com.bisphone.sarf.implv1.util.{ StreamConfig, TCPConfigForClient, TCPConfigForServer }
import com.bisphone.util.{ AsyncResult, ByteOrder }
import org.scalatest.{ BeforeAndAfter, FlatSpecLike, Matchers }
import org.scalatest.concurrent.ScalaFutures
import org.slf4j.LoggerFactory
import com.bisphone.std._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{ Second, Span }
import util.BaseSuite

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.control.NonFatal

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class SayHelloSuite
    extends TestKit(ActorSystem())
        with BaseSuite {

    import util.SayProtocol._

    implicit val ec = ExecutionContext.Implicits.global

    val tcpServer = TCPConfigForServer("localhost", 2424, 100)

    val tcpClient = TCPConfigForClient("localhost", 2424, 10 seconds)

    val stream = StreamConfig(2000, order, 10)

    // val stat = StatCollector("server-stat", StatCollector.Config(1 minute, 3), LoggerFactory.getLogger("server-stat"))(system)


    val serverName = "single"

    val res = for {
        server <- util.Server.tcp(serverName, tcpServer, stream)
        client <-
        TCPClient[Tracked, Untracked](
            "sayclient",
            tcpClient,
            stream,
            writer,
            reader
        )
    } yield (server, client)


    val (server, client) = try Await.result(res, 1 minute) catch {
        case NonFatal(cause) => info(s"Error: ${cause}")
            cause.printStackTrace()
            fail("Can't prepare resources")
    }

    // ==============================================

    "SayHello" must "send & receive commands" in {

        val name = "Reza"

        client(SayHello(name)) onRight { _.name shouldEqual s"'${name}' from '${serverName}'" }

        client(SayBye(name)) onLeft { _ shouldEqual Error(s"Ops from '${serverName}'") }
    }

}
