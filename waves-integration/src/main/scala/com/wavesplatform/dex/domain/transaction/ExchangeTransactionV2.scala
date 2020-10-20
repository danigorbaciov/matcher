package com.wavesplatform.dex.domain.transaction

import com.google.common.primitives.{Ints, Longs}
import com.wavesplatform.dex.domain.account.PrivateKey
import com.wavesplatform.dex.domain.bytes.ByteStr
import com.wavesplatform.dex.domain.bytes.deser.EntityParser.Stateful
import com.wavesplatform.dex.domain.crypto
import com.wavesplatform.dex.domain.crypto.Proofs
import com.wavesplatform.dex.domain.error.ValidationError
import com.wavesplatform.dex.domain.order.Order
import com.wavesplatform.dex.domain.transaction.ExchangeTransaction._
import io.swagger.annotations.{ApiModel, ApiModelProperty}
import monix.eval.Coeval

import scala.util.Try

@ApiModel(value = "ExchangeTransaction")
case class ExchangeTransactionV2(
  @ApiModelProperty(name = "order1", dataType = "com.wavesplatform.dex.domain.order.OrderV3") buyOrder: Order,
  @ApiModelProperty(name = "order2", dataType = "com.wavesplatform.dex.domain.order.OrderV3") sellOrder: Order,
  @ApiModelProperty() amount: Long,
  @ApiModelProperty() price: Long,
  @ApiModelProperty() buyMatcherFee: Long,
  @ApiModelProperty() sellMatcherFee: Long,
  @ApiModelProperty() fee: Long,
  @ApiModelProperty() timestamp: Long,
  @ApiModelProperty(
    value = "Exchange Transaction proofs as Base58 encoded signatures list",
    dataType = "List[string]"
  ) proofs: Proofs
) extends ExchangeTransaction {

  import ExchangeTransactionV2._

  @ApiModelProperty(dataType = "integer", example = "2", allowableValues = "1, 2")
  override val version: Byte = 2

  @ApiModelProperty(hidden = true)
  override val bodyBytes: Coeval[Array[Byte]] =
    Coeval.evalOnce(
      Array(0: Byte, ExchangeTransaction.typeId, version) ++
      Ints.toByteArray(buyOrder.bytes().length) ++ orderMark(buyOrder.version) ++ buyOrder.bytes() ++
      Ints.toByteArray(sellOrder.bytes().length) ++ orderMark(sellOrder.version) ++ sellOrder.bytes() ++
      Longs.toByteArray(price) ++ Longs.toByteArray(amount) ++
      Longs.toByteArray(buyMatcherFee) ++ Longs.toByteArray(sellMatcherFee) ++ Longs.toByteArray(fee) ++
      Longs.toByteArray(timestamp)
    )

  @ApiModelProperty(hidden = true)
  override val bytes: Coeval[Array[Byte]] = Coeval.evalOnce(bodyBytes() ++ proofs.bytes())

}

object ExchangeTransactionV2 extends ExchangeTransactionParser[ExchangeTransactionV2] {

  private def orderMark(version: Byte): Array[Byte] = if (version == 1) Array(1: Byte) else Array()

  def create(
    matcher: PrivateKey,
    buyOrder: Order,
    sellOrder: Order,
    amount: Long,
    price: Long,
    buyMatcherFee: Long,
    sellMatcherFee: Long,
    fee: Long,
    timestamp: Long
  ): Either[ValidationError, ExchangeTransactionV2] =
    create(buyOrder, sellOrder, amount, price, buyMatcherFee, sellMatcherFee, fee, timestamp, Proofs.empty).map { unverified =>
      unverified.copy(proofs = Proofs(List(ByteStr(crypto.sign(matcher, unverified.bodyBytes())))))
    }

  def create(
    buyOrder: Order,
    sellOrder: Order,
    amount: Long,
    price: Long,
    buyMatcherFee: Long,
    sellMatcherFee: Long,
    fee: Long,
    timestamp: Long,
    proofs: Proofs
  ): Either[ValidationError, ExchangeTransactionV2] =
    validateExchangeParams(
      buyOrder,
      sellOrder,
      amount,
      price,
      buyMatcherFee,
      sellMatcherFee,
      fee,
      timestamp
    ).map { _ =>
      ExchangeTransactionV2(buyOrder, sellOrder, amount, price, buyMatcherFee, sellMatcherFee, fee, timestamp, proofs)
    }

  override protected def parseHeader(bytes: Array[Byte]): Try[Int] = Try {

    if (bytes.length < 3) throw new IllegalArgumentException(s"The buffer is too small, it has ${bytes.length} elements")

    val Array(parsedMark, parsedTypeId, parsedVersion) = bytes.take(3)

    if (parsedMark != 0) throw new IllegalArgumentException(s"Expected the '0' byte, but got '$parsedMark'")
    if (parsedTypeId != typeId) throw new IllegalArgumentException(s"Expected type of transaction '$typeId', but got '$parsedTypeId'")
    if (parsedVersion != 2) throw new IllegalArgumentException(s"Expected version of transaction 2, but got '$parsedVersion'")

    3
  }

  override def statefulParse: Stateful[ExchangeTransactionV2] =
    for {
      _ <- read[Int]
      buyOrder <- Order.statefulParse
      _ <- read[Int]
      sellOrder <- Order.statefulParse
      price <- read[Long]
      amount <- read[Long]
      buyMatcherFee <- read[Long]
      sellMatcherFee <- read[Long]
      fee <- read[Long]
      timestamp <- read[Long]
      proofs <- read[Proofs]
    } yield ExchangeTransactionV2(buyOrder, sellOrder, amount, price, buyMatcherFee, sellMatcherFee, fee, timestamp, proofs)

}
