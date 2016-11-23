package com.bisphone.sarf.implv1.util

import akka.actor.{Actor, AllForOneStrategy, Props, SupervisorStrategy}

import scala.concurrent.duration._

/**
  * @author Reza Samei <reza.samei.g@gmail.com>
  */
private[implv1] class GeneralSupervisor (
   props: Props,
   maxNrOfRetries: Int,
   withinTimeRange: FiniteDuration,
   loggingEnabled: Boolean,
   recoveryStrategy: PartialFunction[Throwable, SupervisorStrategy.Directive]
) extends Actor {

   override val supervisorStrategy = AllForOneStrategy(
      maxNrOfRetries = maxNrOfRetries,
      withinTimeRange = withinTimeRange,
      loggingEnabled = loggingEnabled
   )(recoveryStrategy)

   val ref = context.actorOf(props, "actor")

   override def receive: Receive = {
      case msg => ref.tell(msg, sender)
   }

   override def postStop (): Unit = {
      context.children.foreach { child => context stop child }
   }

}

object GeneralSupervisor {
   def props (
      props: Props,
      maxNrOfRetries: Int,
      withinTimeRange: FiniteDuration,
      loggingEnabled: Boolean
   )(
      recoveryStrategy: PartialFunction[Throwable, SupervisorStrategy.Directive]
   ) = Props {
      new GeneralSupervisor(
         props,
         maxNrOfRetries,
         withinTimeRange,
         loggingEnabled,
         recoveryStrategy
      )
   }
}