package org.ergoplatform

import akka.actor.{ActorRef, Props}
import org.ergoplatform.api.routes.{HistoryApiRoute, StateApiRoute}
import org.ergoplatform.local.ErgoMiner.StartMining
import org.ergoplatform.local.TransactionGenerator.StartGeneration
import org.ergoplatform.local.{ErgoLocalInterface, ErgoMiner, TransactionGenerator}
import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.modifiers.mempool.AnyoneCanSpendTransaction
import org.ergoplatform.modifiers.mempool.proposition.AnyoneCanSpendProposition
import org.ergoplatform.nodeView.ErgoNodeViewHolder
import org.ergoplatform.nodeView.history.{ErgoSyncInfo, ErgoSyncInfoMessageSpec}
import org.ergoplatform.settings.ErgoSettings
import scorex.core.api.http.{ApiRoute, PeersApiRoute, UtilsApiRoute}
import scorex.core.app.Application
import scorex.core.network.NodeViewSynchronizer
import scorex.core.network.message.MessageSpec
import org.ergoplatform.Version.VersionString
import scorex.core.settings.ScorexSettings

import scala.concurrent.ExecutionContextExecutor

class ErgoApp(args: Seq[String]) extends Application {
  override type P = AnyoneCanSpendProposition.type
  override type TX = AnyoneCanSpendTransaction
  override type PMOD = ErgoPersistentModifier
  override type NVHT = ErgoNodeViewHolder[_]

  implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher

  lazy val ergoSettings: ErgoSettings = ErgoSettings.read(args.headOption)

  //TODO remove after Scorex update
  override implicit lazy val settings: ScorexSettings = ergoSettings.scorexSettings

  override protected lazy val additionalMessageSpecs: Seq[MessageSpec[_]] = Seq(ErgoSyncInfoMessageSpec)
  override val nodeViewHolderRef: ActorRef = ErgoNodeViewHolder.createActor(actorSystem, ergoSettings)

  override val apiRoutes: Seq[ApiRoute] = Seq(
    UtilsApiRoute(settings.restApi),
    PeersApiRoute(peerManagerRef, networkController, settings.restApi),
    HistoryApiRoute(nodeViewHolderRef, settings.restApi, ergoSettings.nodeSettings.ADState),
    StateApiRoute(nodeViewHolderRef, settings.restApi, ergoSettings.nodeSettings.ADState))

  override val apiTypes: Set[Class[_]] = Set(
    classOf[UtilsApiRoute],
    classOf[PeersApiRoute],
    classOf[HistoryApiRoute],
    classOf[StateApiRoute]
  )

  val minerRef: ActorRef = actorSystem.actorOf(Props(classOf[ErgoMiner], ergoSettings, nodeViewHolderRef))

  if (ergoSettings.nodeSettings.mining && ergoSettings.nodeSettings.offlineGeneration) {
    minerRef ! StartMining
  }

  override val localInterface: ActorRef = actorSystem.actorOf(
    Props(classOf[ErgoLocalInterface], nodeViewHolderRef, minerRef, ergoSettings)
  )

  override val nodeViewSynchronizer: ActorRef = actorSystem.actorOf(
    Props(new NodeViewSynchronizer[P, TX, ErgoSyncInfo, ErgoSyncInfoMessageSpec.type]
    (networkController, nodeViewHolderRef, localInterface, ErgoSyncInfoMessageSpec, settings.network)))

  //only a miner is generating tx load
  //    val txGen = actorSystem.actorOf(Props(classOf[TransactionGenerator], nodeViewHolderRef))
  //    txGen ! StartGeneration

}

object ErgoApp extends App {
  new ErgoApp(args).run()

  def forceStopApplication(): Unit = new Thread(() => System.exit(1), "ergo-platform-shutdown-thread").start()
}
