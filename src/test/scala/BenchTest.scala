import akka.actor.ActorSystem
import akka.util.ByteString
import com.bisphone.sarf.implv1.{Service, TCPClient, TCPServer}
import com.bisphone.sarf.{FailureHandler, Func, IOCommand}
import com.bisphone.sarf.implv1.util.{StreamConfig, TCPConfigForClient, TCPConfigForServer}
import com.bisphone.std._
import com.bisphone.testkit._
import com.bisphone.util.AsyncResult

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class BenchTest extends BaseSuite {

    import util.SayProtocol._

    val timeout = 10 seconds
    val host = "localhost"
    val port = 2424
    val times = 1000
    val concurrency = 100

    implicit val ec = ExecutionContext.Implicits.global
    implicit val system = ActorSystem("Benchmark")

    val tcpServer = TCPConfigForServer(host, port, 100)
    val tcpClient = TCPConfigForClient(host, port, timeout)
    val stream = StreamConfig(2000, order, 10)

    val service = {

        val failureHandler = new FailureHandler {
            def apply (cause: Throwable, bytes: ByteString): Future[IOCommand] = {
                Future successful IOCommand.Close
            }
        }

        val x = Func[SayBye] { sayBye => Error("Ops").asyncLeft }

        new Service.Builder[Tracked, Untracked](
            system.dispatcher,
            failureHandler,
            reader,
            writer,
            None
            // Compiler Error for This: "serveFunc{ (sayHello: SayHello) => ... }"
        ).serveFunc[SayHello]{ sayHello =>
            AsyncResult right Hello(sayHello.to)
            // AsyncResult right Bye(rq.to)
        }.serveFunc(x).result.get
    }

    val res = for {
        server <-
        TCPServer(
            "say-server",
            tcpServer, stream, true
        )(service)
        client <- TCPClient[Tracked, Untracked](
            "sayclient",
            tcpClient,
            stream,
            writer,
            reader
        )
    } yield (server, client)

    val (_, client) = try Await.result(res, timeout) catch {
        case NonFatal(cause) => info(s"Error: ${cause}")
            cause.printStackTrace()
            fail("Can't prepare resources")
    }

    def runJob(list: Seq[Int]): StdTry[_] = {
        val fts = list.map { i =>
            client[SayHello](SayHello("Ali")).asFuture.map {
                case StdRight(value) => ()
                case StdLeft(error) => println(s"Error: ${error.value}")
            }
        }

        Try{Await.result(Future sequence fts, timeout)}.recover {
            case cause: Throwable =>
                cause.printStackTrace()
                throw cause
        }
    }

    "?" must "?" in {}

    it must "try to sayhello for 1M times" in {

        val job = Stream.range(1, times, 1).grouped(concurrency).map { times =>
            println(times.toList)
            runJob(times).get
        }

        job.foreach { _ => println("# Next Step ==================================") }

    }

}
