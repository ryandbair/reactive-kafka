/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.kafka.internal

import akka.actor.{ActorRef, Terminated}
import akka.kafka.Subscriptions.{Assignment, AssignmentWithOffset}
import akka.kafka.{KafkaConsumerActor, ManualSubscription}
import akka.stream.SourceShape
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage.{GraphStageLogic, OutHandler}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

import scala.annotation.tailrec

private[kafka] abstract class ExternalSingleSourceLogic[K, V, Msg](
    val shape: SourceShape[Msg],
    consumer: ActorRef,
    subscription: ManualSubscription
) extends GraphStageLogic(shape) with PromiseControl with MessageBuilder[K, V, Msg] {
  var tps = Set.empty[TopicPartition]
  var buffer: Iterator[ConsumerRecord[K, V]] = Iterator.empty
  var requested = false
  var requestId = 0
  var self: StageActor = _

  override def preStart(): Unit = {
    super.preStart()

    self = getStageActor {
      case (sender, msg: KafkaConsumerActor.Internal.Messages[K, V]) =>
        // might be more than one in flight when we assign/revoke tps
        if (msg.requestId == requestId)
          requested = false
        // do not use simple ++ because of https://issues.scala-lang.org/browse/SI-9766
        if (buffer.hasNext) {
          buffer = buffer ++ msg.messages
        }
        else {
          buffer = msg.messages
        }
        pump()
      case (_, Terminated(ref)) if ref == consumer =>
        failStage(new Exception("Consumer actor terminated"))
    }
    self.watch(consumer)

    subscription match {
      case Assignment(topics) =>
        consumer.tell(KafkaConsumerActor.Internal.Assign(topics), self.ref)
        tps ++= topics
      case AssignmentWithOffset(topics) =>
        consumer.tell(KafkaConsumerActor.Internal.AssignWithOffset(topics), self.ref)
        tps ++= topics.keySet
    }
  }

  val partitionAssignedCB = getAsyncCallback[Iterable[TopicPartition]] { newTps =>
    tps ++= newTps
    requestMessages()
  }
  val partitionRevokedCB = getAsyncCallback[Iterable[TopicPartition]] { newTps =>
    tps --= newTps
    requestMessages()
  }

  @tailrec
  private def pump(): Unit = {
    if (isAvailable(shape.out)) {
      if (buffer.hasNext) {
        val msg = buffer.next()
        push(shape.out, createMessage(msg))
        pump()
      }
      else if (!requested && tps.nonEmpty) {
        requestMessages()
      }
    }
  }

  private def requestMessages(): Unit = {
    requested = true
    requestId += 1
    consumer.tell(KafkaConsumerActor.Internal.RequestMessages(requestId, tps), self.ref)
  }

  setHandler(shape.out, new OutHandler {
    override def onPull(): Unit = {
      pump()
    }
  })

  override def performShutdown() = {
    completeStage()
  }

  override def postStop(): Unit = {
    onShutdown()
    super.postStop()
  }
}
