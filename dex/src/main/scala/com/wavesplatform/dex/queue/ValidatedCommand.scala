package com.wavesplatform.dex.queue

import cats.syntax.either._
import com.google.common.primitives.Longs
import com.wavesplatform.dex.actors.address.AddressActor.Command.Source
import com.wavesplatform.dex.domain.account.Address
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.bytes.ByteStr
import com.wavesplatform.dex.domain.crypto.DigestSize
import com.wavesplatform.dex.domain.order.Order
import com.wavesplatform.dex.model.{LimitOrder, MarketOrder}
import com.wavesplatform.dex.tool.KamonTraceUtils.{readCtx, writeCtx}
import kamon.Kamon
import kamon.context.Context

import scala.util.hashing.MurmurHash3.productHash

sealed trait ValidatedCommand extends Product with Serializable {
  def assetPair: AssetPair
  def maybeCtx: Option[Context]
}

object ValidatedCommand {

  final case class PlaceOrder(limitOrder: LimitOrder, maybeCtx: Option[Context] = Some(Kamon.currentContext())) extends ValidatedCommand {
    override def assetPair: AssetPair = limitOrder.order.assetPair
    override def toString: String = s"PlaceOrder(${limitOrder.order.idStr()})"
    override def hashCode(): Int = productHash(Tuple1(limitOrder))

    override def equals(obj: Any): Boolean = obj match {
      case that: PlaceOrder =>
        limitOrder == that.limitOrder
      case _ => false
    }

  }

  final case class PlaceMarketOrder(marketOrder: MarketOrder, maybeCtx: Option[Context] = Some(Kamon.currentContext()))
      extends ValidatedCommand {
    override def assetPair: AssetPair = marketOrder.order.assetPair

    override def toString: String =
      s"PlaceMarketOrder(${marketOrder.order.idStr()}, k=${marketOrder.order.assetPair.key}, afs=${marketOrder.availableForSpending})"

    override def hashCode(): Int = productHash(Tuple1(marketOrder))

    override def equals(obj: Any): Boolean = obj match {
      case that: PlaceMarketOrder =>
        marketOrder == that.marketOrder
      case _ => false
    }

  }

  final case class CancelOrder(
    assetPair: AssetPair,
    orderId: Order.Id,
    source: Source,
    maybeOwner: Option[Address],
    maybeCtx: Option[Context] = Some(Kamon.currentContext())
  ) extends ValidatedCommand {
    override def toString: String = s"CancelOrder($orderId, ${assetPair.key}, $source, $maybeOwner)"

    override def hashCode(): Int = productHash((assetPair, orderId, source, maybeOwner))

    override def equals(obj: Any): Boolean = obj match {
      case that: CancelOrder =>
        assetPair == that.assetPair && orderId == that.orderId && source == that.source && maybeOwner == that.maybeOwner
      case _ => false
    }

  }

  final case class DeleteOrderBook(assetPair: AssetPair, maybeCtx: Option[Context] = Some(Kamon.currentContext())) extends ValidatedCommand {
    override def toString: String = s"DeleteOrderBook(${assetPair.key})"
    override def hashCode(): Int = productHash(Tuple1(assetPair))

    override def equals(obj: Any): Boolean = obj match {
      case that: DeleteOrderBook =>
        assetPair == that.assetPair
      case _ => false
    }

  }

  implicit final class ValidatedCommandOps(val self: ValidatedCommand) extends AnyVal {

    def assets: Set[Asset] = self match {
      case x: PlaceOrder => x.assetPair.assets + x.limitOrder.order.feeAsset
      case x: PlaceMarketOrder => x.assetPair.assets + x.marketOrder.order.feeAsset
      case x: CancelOrder => x.assetPair.assets
      case x: DeleteOrderBook => x.assetPair.assets
    }

  }

  def toBytes(x: ValidatedCommand): Array[Byte] =
    x match {
      case PlaceOrder(lo, maybeCtx) =>
        (1: Byte) +: Array.concat(Array(lo.order.version), lo.order.bytes(), writeMaybeCtx(maybeCtx))
      case CancelOrder(assetPair, orderId, source, maybeOwner, maybeCtx) =>
        (2: Byte) +: Array.concat(assetPair.bytes, orderId.arr, sourceToBytes(source), writeMaybeAddress(maybeOwner), writeMaybeCtx(maybeCtx))
      case DeleteOrderBook(assetPair, ctx) =>
        (3: Byte) +: Array.concat(assetPair.bytes, writeMaybeCtx(ctx))
      case PlaceMarketOrder(mo, ctx) =>
        (4: Byte) +: Array.concat(Longs.toByteArray(mo.availableForSpending), Array(mo.order.version), mo.order.bytes(), writeMaybeCtx(ctx))
    }

  def fromBytes(xs: Array[Byte]): ValidatedCommand =
    xs.head match {
      case 1 =>
        val bodyBytes = xs.tail
        val (offset, order) = Order.fromBytes(bodyBytes(0), bodyBytes.slice(1, Int.MaxValue))
        val maybeCtx = readMaybeCtx(bodyBytes.drop(offset.value + 1))
        PlaceOrder(LimitOrder(order), maybeCtx)
      case 2 =>
        val bodyBytes = xs.tail
        val (assetPair, offset1) = AssetPair.fromBytes(bodyBytes)
        val offset2 = offset1 + DigestSize
        val orderId = ByteStr(bodyBytes.slice(offset1, offset2))
        val source = bytesToSource(bodyBytes.drop(offset2))
        val (maybeOwner, len) = readMaybeAddress(bodyBytes.drop(offset2 + 1))
        val offset3 = offset2 + 1 + len
        val maybeCtx = readMaybeCtx(bodyBytes.drop(offset3))
        CancelOrder(assetPair, orderId, source, maybeOwner, maybeCtx)
      case 3 =>
        val bodyBytes = xs.tail
        val (assetPair, offset) = AssetPair.fromBytes(bodyBytes)
        val remainingBytes = bodyBytes.drop(offset)
        val ctx = readMaybeCtx(remainingBytes)
        DeleteOrderBook(assetPair, ctx)
      case 4 =>
        val bodyBytes = xs.tail
        val afs = Longs.fromByteArray(bodyBytes.slice(0, 8))
        val (offset, order) = Order.fromBytes(bodyBytes(8), bodyBytes.slice(9, Int.MaxValue))
        val consumedBytesLen = offset.value + 1
        val remainingBytes = bodyBytes.drop(8 + consumedBytesLen)
        val ctx = readMaybeCtx(remainingBytes)
        PlaceMarketOrder(MarketOrder(order, afs), ctx)
      case x =>
        throw new IllegalArgumentException(s"Unknown command type: $x")
    }

  // Pre-allocated
  private val sourceToBytes: Map[Source, Array[Byte]] = Map(
    Source.NotTracked -> Array.emptyByteArray,
    Source.Request -> Array(1),
    Source.Expiration -> Array(2),
    Source.BalanceTracking -> Array(3)
  )

  private def writeMaybeCtx(ctx: Option[Context]): Array[Byte] =
    ctx.map(writeCtx).getOrElse(Array.emptyByteArray)

  private def readMaybeCtx(bytes: Array[Byte]): Option[Context] =
    if (bytes.length > 0)
      Either.catchNonFatal(readCtx(bytes)).toOption
    else
      None

  private def writeMaybeAddress(maybeAddress: Option[Address]): Array[Byte] =
    maybeAddress.map(_.bytes.arr).getOrElse(Array.emptyByteArray)

  private def readMaybeAddress(bytes: Array[Byte]): (Option[Address], Int) =
    if (bytes.length > 0) {
      val maybeAddress = Address.fromBytes(bytes).toOption
      if (maybeAddress.isDefined)
        maybeAddress -> Address.AddressLength
      else
        maybeAddress -> 0
    } else
      None -> 0

  private def bytesToSource(xs: Array[Byte]): Source =
    if (xs.isEmpty) Source.NotTracked
    else
      xs.head match {
        case 1 => Source.Request
        case 2 => Source.Expiration
        case 3 => Source.BalanceTracking
        case x => throw new IllegalArgumentException(s"Unknown source type: $x")
      }

}
