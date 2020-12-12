package com.wavesplatform.dex.grpc.integration.clients.matcherext

import cats.syntax.option._
import com.wavesplatform.dex.domain.utils.ScorexLogging
import com.wavesplatform.dex.grpc.integration.clients.domain.WavesNodeEvent
import com.wavesplatform.dex.grpc.integration.protobuf.PbToDexConversions._
import com.wavesplatform.dex.grpc.integration.services.UtxEvent

object UtxEventConversions extends ScorexLogging {

  def toEvent(event: UtxEvent): Option[WavesNodeEvent] = event.`type` match {
    case UtxEvent.Type.Switch(event) => WavesNodeEvent.UtxSwitched(event.transactions).some
    case UtxEvent.Type.Update(event) =>
      val failedTxs = event.removed.flatMap { tx =>
        tx.reason match {
          case None => none // Because we remove them during adding a full/micro block
          case Some(reason) =>
            tx.transaction.tapEach { tx =>
              log.info(s"${tx.id.toVanilla} failed: ${reason.name}, ${reason.message}")
            }
        }
      }
      if (event.added.isEmpty && failedTxs.isEmpty) none
      else WavesNodeEvent.UtxUpdated(event.added.flatMap(_.transaction), failedTxs).some
    case _ => none
  }

}
