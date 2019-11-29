/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.pattern._
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Transaction}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient, JsonRPCError}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateResult}
import fr.acinq.eclair.wire.ChannelAnnouncement
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JString, _}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits.ByteVector

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global


class ExtendedBitcoinClientSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false))
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

  test("send transaction idempotent") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))

    val sender = TestProbe()
    bitcoinClient.invoke("getnewaddress").pipeTo(sender.ref)
    val JString(address) = sender.expectMsgType[JString]
    bitcoinClient.invoke("createrawtransaction", Array.empty, Map(address -> 6)).pipeTo(sender.ref)
    val JString(noinputTx) = sender.expectMsgType[JString]
    bitcoinClient.invoke("fundrawtransaction", noinputTx).pipeTo(sender.ref)
    val json = sender.expectMsgType[JValue]
    val JString(unsignedtx) = json \ "hex"
    val JInt(changePos) = json \ "changepos"
    bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx).pipeTo(sender.ref)
    val JString(signedTx) = sender.expectMsgType[JValue] \ "hex"
    val tx = Transaction.read(signedTx)
    val txid = tx.txid.toString()
  }

  test("validate short channel Ids") {
    val sender = TestProbe()
    val client = new ExtendedBitcoinClient(bitcoinrpcclient)
    val channelTransaction = ExternalWalletHelper.nonWalletTransaction(system) // create a non wallet transaction
    client.publishTransaction(channelTransaction).pipeTo(sender.ref)
    sender.expectMsgType[String]

    val List(blockHash) = generateBlocks(bitcoincli, 1)

    client.getBlockCount.pipeTo(sender.ref)
    val height = sender.expectMsgType[Long]

    bitcoinrpcclient.invoke("getblock", blockHash, 0).pipeTo(sender.ref)
    val JString(rawBlock) = sender.expectMsgType[JString]
    val block = Block.read(rawBlock)
    val txIndex = block.tx.indexWhere(_.txid == channelTransaction.txid)

    val channelShortId = ShortChannelId(height.toInt, txIndex, 0)

    // we won't be able to get the raw transaction if it's non-wallet
    client.getRawTransaction(channelTransaction.txid.toHex).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    // likewise we can't get the channel short id from the txId
    client.getTransactionShortId(channelTransaction.txid.toHex).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    val mockChannelAnnouncement = ChannelAnnouncement(
      nodeSignature1 = ByteVector64.Zeroes,
      nodeSignature2 = ByteVector64.Zeroes,
      bitcoinSignature1 = ByteVector64.Zeroes,
      bitcoinSignature2 = ByteVector64.Zeroes,
      features = ByteVector.empty,
      chainHash = ByteVector32.Zeroes,
      shortChannelId = channelShortId,
      nodeId1 = PrivateKey(randomBytes32).publicKey,
      nodeId2 = PrivateKey(randomBytes32).publicKey,
      bitcoinKey1 = PrivateKey(randomBytes32).publicKey,
      bitcoinKey2 = PrivateKey(randomBytes32).publicKey
    )

    // but we can validate if a short channel id is unspent
    client.validate(mockChannelAnnouncement).pipeTo(sender.ref)
    sender.expectMsg(ValidateResult(mockChannelAnnouncement, Right(channelTransaction, UtxoStatus.Unspent)))

    // if the output does not exist, validation fails
    client.validate(mockChannelAnnouncement.copy(shortChannelId = ShortChannelId(10, 10, 10))).pipeTo(sender.ref)
    val validationResult = sender.expectMsgType[ValidateResult]
    assert(validationResult.fundingTx.isLeft)
  }

  test("importmulti should import several watch addresses at once") {
    val sender = TestProbe()
    val client = new ExtendedBitcoinClient(bitcoinrpcclient)
    val externalTx1 = ExternalWalletHelper.nonWalletTransaction(system) // create a non wallet transaction
    val externalTx2 = ExternalWalletHelper.spendNonWalletTx(externalTx1, receivingKeyIndex = 21)// spend non wallet transaction

    client.publishTransaction(externalTx1).pipeTo(sender.ref)
    client.publishTransaction(externalTx2).pipeTo(sender.ref)

    sender.expectMsgType[String]
    sender.expectMsgType[String]

    val List(blockHash) = generateBlocks(bitcoincli, 1)

    bitcoinrpcclient.invoke("getblock", blockHash, 1).map(_ \ "height").pipeTo(sender.ref)
    val earliestBlockHeight= sender.expectMsgType[JInt].num.longValue()

    // when not imported transactions can't be looked up
    client.getTransaction(externalTx1.txid.toHex).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    client.getTransaction(externalTx2.txid.toHex).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    val importAndScan = for {
      _ <- client.importMulti(Seq(externalTx1.txOut.head.publicKeyScript, externalTx2.txOut.head.publicKeyScript))
      _ <- client.rescanBlockChain(earliestBlockHeight)
    } yield Unit

    Await.ready(importAndScan, 30 seconds)

    client.getTransaction(externalTx1.txid.toHex).pipeTo(sender.ref)
    sender.expectMsg(externalTx1)

    client.getTransaction(externalTx2.txid.toHex).pipeTo(sender.ref)
    sender.expectMsg(externalTx2)
  }

}