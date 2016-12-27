package com.bisphone.sarf.implv1.util

import akka.actor._
import org.slf4j.LoggerFactory

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
class SimpleSupervisor (
    name: String,
    actor: ActorRef,
    fn: ActorRef => Unit
) extends Actor {

    val loggerName = s"SimpleSupervisor($name)"

    val logger = LoggerFactory getLogger loggerName

    var stopped = false

    override def preStart(): Unit = {
        context watch actor
        if (logger isWarnEnabled ()) logger warn
            s"""{
               |"subject":  "${loggerName}.preStart",
               |"actor":    "${self}",
               |"child":    "${actor}"
               |}""".stripMargin
    }

    override def postStop(): Unit = {
        if (!stopped) {

            if (logger isWarnEnabled ()) logger warn
                s"""{
                   |"subject":  "${loggerName}.postStop",
                   |"actor":    "${self}",
                   |"child":    "${actor}",
                   |"status":   "KillChild"
                   |}""".stripMargin

            actor ! PoisonPill
        } else {
            if (logger isWarnEnabled ()) logger warn
                s"""{
                   |"subject":  "${loggerName}.postStop",
                   |"actor":    "${self}",
                   |"child":    "${actor}",
                   |"status":   "ChildHasStopped"
                   |}""".stripMargin
        }
    }

    override def receive = {
        case Terminated(ref) =>
            stopped = true
            fn(ref)
            context stop self
    }

}
