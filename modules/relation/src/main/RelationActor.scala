package lila.relation

import akka.actor.Actor
import akka.pattern.pipe
import scala.concurrent.duration._

import actorApi._
import lila.common.LightUser
import lila.hub.actorApi.relation._
import lila.hub.actorApi.{ SendTo, SendTos }

import play.api.libs.json.Json

private[relation] final class RelationActor(
    lightUser: LightUser.GetterSync,
    api: RelationApi,
    online: OnlineDoing
) extends Actor {

  private val bus = context.system.lilaBus

  private var previousOnlineIds = Set.empty[ID]

  override def preStart(): Unit = {
    context.system.lilaBus.subscribe(self, 'startGame, 'finishGame, 'study)
  }

  override def postStop(): Unit = {
    super.postStop()
    context.system.lilaBus.unsubscribe(self)
  }

  def receive = {

    case GetOnlineFriends(userId) => onlineFriends(userId) pipeTo sender

    // triggers following reloading for this user id
    case ReloadOnlineFriends(userId) => onlineFriends(userId) foreach { res =>
      bus.publish(SendTo(userId, JsonView.writeOnlineFriends(res)), 'users)
    }

    case ComputeMovement =>
      val curIds = online.userIds.keySet
      val leaveUsers = (previousOnlineIds diff curIds).toList flatMap { lightUser(_) }
      val enterUsers = (curIds diff previousOnlineIds).toList flatMap { lightUser(_) }

      val friendsEntering = enterUsers map { u =>
        FriendEntering(u, online.playing get u.id, online.studying.getIfPresent(u.id).isDefined)
      }

      notifyFollowersFriendEnters(friendsEntering)
      notifyFollowersFriendLeaves(leaveUsers)
      previousOnlineIds = curIds

    case lila.game.actorApi.FinishGame(game, _, _) if game.hasClock =>
      val usersPlaying = game.userIds
      online.playing removeAll usersPlaying
      notifyFollowersGameStateChanged(usersPlaying, "following_stopped_playing")

    case lila.game.actorApi.StartGame(game) if game.hasClock =>
      val usersPlaying = game.userIds
      online.playing putAll usersPlaying
      notifyFollowersGameStateChanged(usersPlaying, "following_playing")

    case lila.hub.actorApi.study.StudyDoor(userId, studyId, contributor, public, true) =>
      online.studyingAll.put(userId, studyId)
      if (contributor && public) {
        val wasAlreadyInStudy = online.studying.getIfPresent(userId).isDefined
        online.studying.put(userId, studyId)
        if (!wasAlreadyInStudy) notifyFollowersFriendInStudyStateChanged(userId, studyId, "following_joined_study")
      }

    case lila.hub.actorApi.study.StudyDoor(userId, studyId, contributor, public, false) =>
      online.studyingAll invalidate userId
      if (contributor && public) {
        online.studying invalidate userId
        notifyFollowersFriendInStudyStateChanged(userId, studyId, "following_left_study")
      }

    case lila.hub.actorApi.study.StudyBecamePrivate(studyId, contributors) =>
      val found = online.studying.getAllPresent(contributors).filter(_._2 == studyId)
      val contributorsInStudy = contributors filter found.contains
      for (c <- contributorsInStudy) {
        online.studying invalidate c
        notifyFollowersFriendInStudyStateChanged(c, studyId, "following_left_study")
      }

    case lila.hub.actorApi.study.StudyBecamePublic(studyId, contributors) =>
      val found = online.studyingAll.getAllPresent(contributors).filter(_._2 == studyId)
      val contributorsInStudy = contributors filter found.contains
      for (c <- contributorsInStudy) {
        online.studying.put(c, studyId)
        notifyFollowersFriendInStudyStateChanged(c, studyId, "following_joined_study")
      }

    case lila.hub.actorApi.study.StudyMemberGotWriteAccess(userId, studyId) =>
      if (online.studyingAll.getIfPresent(userId) has studyId) {
        online.studying.put(userId, studyId)
        notifyFollowersFriendInStudyStateChanged(userId, studyId, "following_joined_study")
      }

    case lila.hub.actorApi.study.StudyMemberLostWriteAccess(userId, studyId) =>
      if (online.studying.getIfPresent(userId) has studyId) {
        online.studying invalidate userId
        notifyFollowersFriendInStudyStateChanged(userId, studyId, "following_left_study")
      }
  }

  private def onlineFriends(userId: ID): Fu[OnlineFriends] =
    api fetchFollowing userId map online.userIds.intersect map { friends =>
      OnlineFriends(
        users = friends.flatMap { lightUser(_) },
        playing = filterFriendsPlaying(friends),
        studying = filterFriendsStudying(friends)
      )
    }

  private def filterFriendsPlaying(friendIds: Set[ID]): Set[ID] =
    online.playing intersect friendIds

  private def filterFriendsStudying(friendIds: Set[ID]): Set[ID] = {
    val found = online.studying.getAllPresent(friendIds)
    friendIds filter found.contains
  }

  private def notifyFollowersFriendEnters(friendsEntering: List[FriendEntering]) =
    friendsEntering foreach { entering =>
      api fetchFollowers entering.user.id map online.userIds.intersect foreach { ids =>
        if (ids.nonEmpty) bus.publish(SendTos(ids.toSet, JsonView.writeFriendEntering(entering)), 'users)
      }
    }

  private def notifyFollowersFriendLeaves(friendsLeaving: List[LightUser]) =
    friendsLeaving foreach { leaving =>
      api fetchFollowers leaving.id map online.userIds.intersect foreach { ids =>
        if (ids.nonEmpty) bus.publish(SendTos(ids.toSet, "following_leaves", leaving.titleName), 'users)
      }
    }

  private def notifyFollowersGameStateChanged(userIds: Traversable[ID], message: String) =
    userIds foreach { userId =>
      api fetchFollowers userId map online.userIds.intersect foreach { ids =>
        if (ids.nonEmpty) bus.publish(SendTos(ids.toSet, message, userId), 'users)
      }
    }

  private def notifyFollowersFriendInStudyStateChanged(userId: ID, studyId: String, message: String) =
    api fetchFollowers userId map online.userIds.intersect foreach { ids =>
      if (ids.nonEmpty) bus.publish(SendTos(ids.toSet, message, userId), 'users)
    }
}
