
import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.ByteString
import com.bisphone.sarf._
import com.bisphone.sarf.implv1.{Service, StatCollector}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient, TCPConfigForServer}
import com.bisphone.util.{AsyncResult, ByteOrder}
import org.scalatest.{BeforeAndAfter, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.slf4j.LoggerFactory
import util.TextFrame
import com.bisphone.std._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class ClientAndServerWithNewAbstraction
    extends TestKit(ActorSystem())
        with FlatSpecLike
        with Matchers
        with BeforeAndAfter
        with ScalaFutures {

    implicit val ec = ExecutionContext.Implicits.global

    val tcpServer = TCPConfigForServer("localhost", 2424, 100)

    val tcpClient = TCPConfigForClient("localhost", 2424, 10 seconds)

    val stream = StreamConfig(2000, TextFrame.order, 10)

    val stat = StatCollector("server-stat", StatCollector.Config(1 minute, 3), LoggerFactory.getLogger("server-stat"))(system)

    val service = {
        import util.SayProtocol._

        val failureHandler = new FailureHandler {
            def apply (cause: Throwable, bytes: ByteString): Future[IOCommand] = {
                Future successful IOCommand.Close
            }
        }

        new Service.Builder[Tracked, Untracked](
            system.dispatcher,
            failureHandler,
            reader,
            writer,
            Some(stat)
        ).serveFunc[Hello, Error, SayHello]{ (rq: SayHello) =>
            AsyncResult right Hello(rq.to)
            // AsyncResult right Bye(rq.to)
        }.result.get
    }

}
