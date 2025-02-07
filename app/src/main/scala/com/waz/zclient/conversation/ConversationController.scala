/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.conversation

import java.net.URI

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import com.waz.api
import com.waz.api.{IConversation, Verification}
import com.waz.content.{ConversationStorage, MembersStorage, OtrClientsStorage, UsersStorage}
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.ConversationData.ConversationType
import com.waz.model._
import com.waz.model.otr.Client
import com.waz.service.AccountManager
import com.waz.service.assets2.{Content, ContentForUpload, UriHelper}
import com.waz.service.conversation.{ConversationsService, ConversationsUiService, SelectedConversationService}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue, Threading}
import com.waz.utils.events.{EventContext, EventStream, Signal, SourceStream}
import com.waz.utils.{Serialized, returning, _}
import com.waz.zclient.assets2.ImageCompressUtils
import com.waz.zclient.calling.controllers.CallStartController
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.conversation.ConversationController.ConversationChange
import com.waz.zclient.conversationlist.ConversationListController
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.log.LogUI._
import com.waz.zclient.utils.Callback
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.{Injectable, Injector, R}
import org.threeten.bp.Instant

import scala.concurrent.Future
import scala.concurrent.duration._

class ConversationController(implicit injector: Injector, context: Context, ec: EventContext)
  extends Injectable with DerivedLogTag {

  private implicit val dispatcher = new SerialDispatchQueue(name = "ConversationController")

  private lazy val selectedConv       = inject[Signal[SelectedConversationService]]
  private lazy val convsUi            = inject[Signal[ConversationsUiService]]
  private lazy val conversations      = inject[Signal[ConversationsService]]
  private lazy val convsStorage       = inject[Signal[ConversationStorage]]
  private lazy val membersStorage     = inject[Signal[MembersStorage]]
  private lazy val usersStorage       = inject[Signal[UsersStorage]]
  private lazy val otrClientsStorage  = inject[Signal[OtrClientsStorage]]
  private lazy val account            = inject[Signal[Option[AccountManager]]]
  private lazy val callStart          = inject[CallStartController]
  private lazy val convListController = inject[ConversationListController]
  private lazy val uriHelper          = inject[UriHelper]
  private lazy val accentColorController = inject[AccentColorController]

  private var lastConvId = Option.empty[ConvId]

  val currentConvIdOpt: Signal[Option[ConvId]] = selectedConv.flatMap(_.selectedConversationId)

  val currentConvId: Signal[ConvId] = currentConvIdOpt.collect { case Some(convId) => convId }

  val currentConvOpt: Signal[Option[ConversationData]] =
    currentConvIdOpt.flatMap(_.fold(Signal.const(Option.empty[ConversationData]))(conversationData)) // updates on every change of the conversation data, not only on switching

  val currentConv: Signal[ConversationData] =
    currentConvOpt.collect { case Some(conv) => conv }

  val convChanged: SourceStream[ConversationChange] = EventStream[ConversationChange]()

  def conversationData(convId: ConvId): Signal[Option[ConversationData]] =
    convsStorage.flatMap(_.optSignal(convId))

  def getConversation(convId: ConvId): Future[Option[ConversationData]] =
    convsStorage.head.flatMap(_.get(convId))

  val currentConvType: Signal[ConversationType] = currentConv.map(_.convType).disableAutowiring()
  val currentConvName: Signal[String] = currentConv.map(_.displayName).map {
    case Name.Empty => getString(R.string.default_deleted_username)
    case name => name
  } // the name of the current conversation can be edited (without switching)

  val currentConvIsVerified: Signal[Boolean] = currentConv.map(_.verified == Verification.VERIFIED)
  val currentConvIsGroup: Signal[Boolean] =
    for {
      convs   <- conversations
      convId  <- currentConvId
      isGroup <- convs.groupConversation(convId)
    } yield isGroup

  val currentConvIsTeamOnly: Signal[Boolean] = currentConv.map(_.isTeamOnly)

  lazy val currentConvMembers = for {
    membersStorage <- membersStorage
    selfUserId     <- inject[Signal[UserId]]
    conv           <- currentConvId
    members        <- membersStorage.activeMembers(conv)
  } yield members.filter(_ != selfUserId)

  currentConvId { convId =>
    conversations.head.foreach(_.forceNameUpdate(convId, getString(R.string.default_deleted_username)))
    if (!lastConvId.contains(convId)) { // to only catch changes coming from SE (we assume it's an account switch)
      verbose(l"a conversation change bypassed selectConv: last = $lastConvId, current = $convId")
      convChanged ! ConversationChange(from = lastConvId, to = Option(convId), requester = ConversationChangeRequester.ACCOUNT_CHANGE)
      lastConvId = Option(convId)
    }
  }

  // this should be the only UI entry point to change conv in SE
  def selectConv(convId: Option[ConvId], requester: ConversationChangeRequester): Future[Unit] = convId match {
    case None => Future.successful({})
    case Some(id) =>
      val oldId = lastConvId
      lastConvId = convId
      for {
        selectedConv <- selectedConv.head
        convsUi      <- convsUi.head
        conv         <- getConversation(id)
        _            <- if (conv.exists(_.archived)) convsUi.setConversationArchived(id, archived = false) else Future.successful(Option.empty[ConversationData])
        _            <- selectedConv.selectConversation(convId)
      } yield { // catches changes coming from UI
        verbose(l"changing conversation from $oldId to $convId, requester: $requester")
        convChanged ! ConversationChange(from = oldId, to = convId, requester = requester)
      }
  }

  def selectConv(id: ConvId, requester: ConversationChangeRequester): Future[Unit] =
    selectConv(Some(id), requester)

  def switchConversation(convId: ConvId, call: Boolean = false, delayMs: FiniteDuration = 750.millis) =
    CancellableFuture.delay(delayMs).map { _ =>
      selectConv(convId, ConversationChangeRequester.INTENT).foreach { _ =>
        if (call)
          for {
            Some(acc) <- account.map(_.map(_.userId)).head
            _         <- callStart.startCall(acc, convId)
          } yield ()
      }
    } (Threading.Ui).future

  def groupConversation(id: ConvId): Signal[Boolean] =
    conversations.flatMap(_.groupConversation(id))

  def participantsIds(conv: ConvId): Future[Seq[UserId]] =
    membersStorage.head.flatMap(_.getActiveUsers(conv))

  def setEphemeralExpiration(expiration: Option[FiniteDuration]): Future[Unit] =
    for {
      id <- currentConvId.head
      _  <- convsUi.head.flatMap(_.setEphemeral(id, expiration))
    } yield ()

  def loadMembers(convId: ConvId): Future[Seq[UserData]] =
    for {
      userIds <- membersStorage.head.flatMap(_.getActiveUsers(convId)) // TODO: maybe switch to ConversationsMembersSignal
      users   <- usersStorage.head.flatMap(_.listAll(userIds))
    } yield users

  def loadClients(userId: UserId): Future[Seq[Client]] =
    otrClientsStorage.head.flatMap(_.getClients(userId)) // TODO: move to SE maybe?

  def sendMessage(text:     String,
                  mentions: Seq[Mention] = Nil,
                  quote:    Option[MessageId] = None,
                  exp:      Option[Option[FiniteDuration]] = None): Future[Option[MessageData]] =
    convsUiwithCurrentConv({(ui, id) =>
      quote.fold2(ui.sendTextMessage(id, text, mentions, exp), ui.sendReplyMessage(_, text, mentions, exp))
    })

  def sendTextMessage(convs:    Seq[ConvId],
                      text:     String,
                      mentions: Seq[Mention] = Nil,
                      quote:    Option[MessageId] = None,
                      exp:      Option[Option[FiniteDuration]] = None): Future[Seq[Option[MessageData]]] =
    convsUi.head.flatMap { ui =>
      Future.sequence(convs.map(id =>
        quote.fold2(ui.sendTextMessage(id, text, mentions, exp), ui.sendReplyMessage(_, text, mentions, exp))
      ))
    }

  def sendAssetMessage(content: ContentForUpload): Future[Option[MessageData]] =
    convsUiwithCurrentConv((ui, id) => ui.sendAssetMessage(id, content))

  def sendAssetMessage(content:  ContentForUpload,
                       activity: Activity,
                       exp:      Option[Option[FiniteDuration]]): Future[Option[MessageData]] =
    convsUiwithCurrentConv((ui, id) =>
      accentColorController.accentColor.head.flatMap(color =>
        ui.sendAssetMessage(id, content, (s: Long) => showWifiWarningDialog(s, color), exp))
    )

  private def sendAssetMessage(convs:    Seq[ConvId],
                               content:  ContentForUpload,
                               activity: Activity,
                               exp:      Option[Option[FiniteDuration]]): Future[Seq[Option[MessageData]]] =
    for {
      ui    <- convsUi.head
      color <- accentColorController.accentColor.head
      msgs  <- Future.traverse(convs) { id =>
                 ui.sendAssetMessage(id, content, (s: Long) => showWifiWarningDialog(s, color), exp)
               }
    } yield msgs

  def sendAssetMessage(bitmap: Bitmap, assetName: String): Future[Option[MessageData]] =
    for {
      image   <- Future { ImageCompressUtils.compress(bitmap, CompressFormat.JPEG) }
      content =  ContentForUpload(assetName, Content.Bytes(Mime.Image.Jpg, image))
      msg     <- convsUiwithCurrentConv((ui, id) => ui.sendAssetMessage(id, content))
    } yield msg

  def sendAssetMessage(uri:      URI,
                       activity: Activity,
                       exp:      Option[Option[FiniteDuration]],
                       convs:    Seq[ConvId] = Seq()): Future[Option[MessageData]] =
    for {
      content <- Future.fromTry(uriHelper.extractFileName(uri).map(ContentForUpload(_,  Content.Uri(uri))))
      msg     <- if (convs.isEmpty) sendAssetMessage(content, activity, exp)
                 else sendAssetMessage(convs, content, activity, exp).map(_.head)
    } yield msg

  def sendMessage(location: api.MessageContent.Location): Future[Option[MessageData]] =
    convsUiwithCurrentConv((ui, id) => ui.sendLocationMessage(id, location))

  private def convsUiwithCurrentConv[A](f: (ConversationsUiService, ConvId) => Future[A]): Future[A] =
    for {
      cUi    <- convsUi.head
      convId <- currentConvId.head
      res    <- f(cUi, convId)
    } yield res

  def setCurrentConvName(name: String): Future[Unit] =
    for {
      service     <- convsUi.head
      id          <- currentConvId.head
      currentName <- currentConv.map(_.displayName).head
    } yield {
      val newName = Name(name)
      if (newName != currentName) service.setConversationName(id, newName)
    }

  def setCurrentConvReadReceipts(readReceiptsEnabled: Boolean): Future[Unit] =
    for {
      service             <- convsUi.head
      id                  <- currentConvId.head
      currentReadReceipts <- currentConv.map(_.readReceiptsAllowed).head
    } yield
      if (currentReadReceipts != readReceiptsEnabled)
        service.setReceiptMode(id, if (readReceiptsEnabled) 1 else 0)

  def addMembers(id: ConvId, users: Set[UserId]): Future[Unit] =
    convsUi.head.flatMap(_.addConversationMembers(id, users)).map(_ => {})

  def removeMember(user: UserId): Future[Unit] =
    for {
      id <- currentConvId.head
      _  <- convsUi.head.flatMap(_.removeConversationMember(id, user))
    } yield {}

  def leave(convId: ConvId): CancellableFuture[Unit] =
    returning (Serialized("Conversations", convId)(CancellableFuture.lift(convsUi.head.flatMap(_.leaveConversation(convId))))) { _ =>
      currentConvId.head.map { id => if (id == convId) setCurrentConversationToNext(ConversationChangeRequester.LEAVE_CONVERSATION) }
    }

  def setCurrentConversationToNext(requester: ConversationChangeRequester): Future[Unit] = {
    def nextConversation(convId: ConvId): Future[Option[ConvId]] =
      convListController.regularConversationListData.head.map {
        regular => regular.lift(regular.indexWhere(_.id == convId) + 1).map(_.id)
      } (Threading.Background)

    for {
      currentConvId <- currentConvId.head
      nextConvId    <- nextConversation(currentConvId)
      _             <- selectConv(nextConvId, requester)
    } yield ()
  }

  def archive(convId: ConvId, archive: Boolean): Unit = {
    convsUi.head.flatMap(_.setConversationArchived(convId, archive))
    currentConvId.head.map { id => if (id == convId) CancellableFuture.delayed(ConversationController.ARCHIVE_DELAY){
      if (!archive) selectConv(convId, ConversationChangeRequester.CONVERSATION_LIST_UNARCHIVED_CONVERSATION)
      else setCurrentConversationToNext(ConversationChangeRequester.ARCHIVED_RESULT)
    }}
  }

  def setMuted(id: ConvId, muted: MuteSet): Future[Unit] =
    convsUi.head.flatMap(_.setConversationMuted(id, muted)).map(_ => {})

  def delete(id: ConvId, alsoLeave: Boolean): CancellableFuture[Option[ConversationData]] = {
    def clear(id: ConvId) = Serialized("Conversations", id)(CancellableFuture.lift(convsUi.head.flatMap(_.clearConversation(id))))
    if (alsoLeave) leave(id).flatMap(_ => clear(id)) else clear(id)
  }

  def createGuestRoom(): Future[ConversationData] = createGroupConversation(Some(context.getString(R.string.guest_room_name)), Set(), false, false)

  def createGroupConversation(name: Option[Name], users: Set[UserId], teamOnly: Boolean, readReceipts: Boolean): Future[ConversationData] =
    convsUi.head.flatMap(_.createGroupConversation(name, users, teamOnly, if (readReceipts) 1 else 0)).map(_._1)

  def withCurrentConvName(callback: Callback[String]): Unit = currentConvName.head.foreach(callback.callback)(Threading.Ui)

  def getCurrentConvId: ConvId = currentConvId.currentValue.orNull
  def withConvLoaded(convId: ConvId, callback: Callback[ConversationData]): Unit = getConversation(convId).foreach {
    case Some(data) => callback.callback(data)
    case None =>
  }(Threading.Ui)

  private var convChangedCallbackSet = Set.empty[Callback[ConversationChange]]
  def addConvChangedCallback(callback: Callback[ConversationChange]): Unit = convChangedCallbackSet += callback
  def removeConvChangedCallback(callback: Callback[ConversationChange]): Unit = convChangedCallbackSet -= callback

  convChanged.onUi { ev => convChangedCallbackSet.foreach(callback => callback.callback(ev)) }

  object messages {

    val ActivityTimeout = 3.seconds

    /**
      * Currently focused message.
      * There is only one focused message, switched by tapping.
      */
    val focused = Signal(Option.empty[MessageId])

    /**
      * Tracks last focused message together with last action time.
      * It's not cleared when message is unfocused, and toggleFocus takes timeout into account.
      * This is used to decide if timestamp view should be shown in footer when message has likes.
      */
    val lastActive = Signal((MessageId.Empty, Instant.EPOCH)) // message showing status info

    currentConv.onChanged { _ => clear() }

    def clear() = {
      focused ! None
      lastActive ! (MessageId.Empty, Instant.EPOCH)
    }

    def isFocused(id: MessageId): Boolean = focused.currentValue.flatten.contains(id)

    /**
      * Switches current msg focus state to/from given msg.
      */
    def toggleFocused(id: MessageId) = {
      verbose(l"toggleFocused($id)")
      focused mutate {
        case Some(`id`) => None
        case _ => Some(id)
      }
      lastActive.mutate {
        case (`id`, t) if !ActivityTimeout.elapsedSince(t) => (id, Instant.now - ActivityTimeout)
        case _ => (id, Instant.now)
      }
    }
  }
}

object ConversationController extends DerivedLogTag {
  val ARCHIVE_DELAY = 500.millis
  val MaxParticipants: Int = 500

  case class ConversationChange(from: Option[ConvId], to: Option[ConvId], requester: ConversationChangeRequester) {
    def toConvId: ConvId = to.orNull // TODO: remove when not used anymore
    lazy val noChange: Boolean = from == to
  }

  def getOtherParticipantForOneToOneConv(conv: ConversationData): UserId = {
    if (conv != ConversationData.Empty &&
        conv.convType != IConversation.Type.ONE_TO_ONE &&
        conv.convType != IConversation.Type.WAIT_FOR_CONNECTION &&
        conv.convType != IConversation.Type.INCOMING_CONNECTION)
      error(l"unexpected call, most likely UI error", new UnsupportedOperationException(s"Can't get other participant for: ${conv.convType} conversation"))
    UserId(conv.id.str) // one-to-one conversation has the same id as the other user, so we can access it directly
  }

  lazy val PredefinedExpirations =
    Seq(
      None,
      Some(10.seconds),
      Some(5.minutes),
      Some(1.hour),
      Some(1.day),
      Some(7.days),
      Some(28.days)
    )

  import com.waz.model.EphemeralDuration._
  def getEphemeralDisplayString(exp: Option[FiniteDuration])(implicit context: Context): String = {
    exp.map(EphemeralDuration(_)) match {
      case None              => getString(R.string.ephemeral_message__timeout__off)
      case Some((l, Second)) => getQuantityString(R.plurals.unit_seconds, l.toInt, l.toString)
      case Some((l, Minute)) => getQuantityString(R.plurals.unit_minutes, l.toInt, l.toString)
      case Some((l, Hour))   => getQuantityString(R.plurals.unit_hours,   l.toInt, l.toString)
      case Some((l, Day))    => getQuantityString(R.plurals.unit_days,    l.toInt, l.toString)
      case Some((l, Week))   => getQuantityString(R.plurals.unit_weeks,   l.toInt, l.toString)
      case Some((l, Year))   => getQuantityString(R.plurals.unit_years,   l.toInt, l.toString)
    }
  }

  lazy val MuteSets = Seq(MuteSet.AllAllowed, MuteSet.OnlyMentionsAllowed, MuteSet.AllMuted)

  def muteSetDisplayStringId(muteSet: MuteSet): Int = muteSet match {
    case MuteSet.AllMuted            => R.string.conversation__action__notifications_nothing
    case MuteSet.OnlyMentionsAllowed => R.string.conversation__action__notifications_mentions_and_replies
    case _                           => R.string.conversation__action__notifications_everything
  }
}
