package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.http.scaladsl.util.FastFuture
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType._
import com.advancedtelematic.libtuf.data.TufDataType.RoleType.RoleType
import com.advancedtelematic.libtuf.data.TufDataType.{RepoId, RoleType, SignedPayload}
import com.advancedtelematic.libtuf.keyserver.KeyserverClient
import com.advancedtelematic.tuf.reposerver.data.RepositoryDataType.{SignedRole, TargetItem}
import com.advancedtelematic.tuf.reposerver.db.{SignedRoleRepository, SignedRoleRepositorySupport, TargetItemRepositorySupport}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import slick.jdbc.MySQLProfile.api._
import com.advancedtelematic.tuf.reposerver.db.SignedRoleRepository.SignedRoleNotFound

import scala.async.Async._
import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.either._
import org.slf4j.LoggerFactory

class SignedRoleGeneration(keyserverClient: KeyserverClient)
                          (implicit val db: Database, val ec: ExecutionContext) extends SignedRoleRepositorySupport {

  private val log = LoggerFactory.getLogger(this.getClass)

  val targetRoleGeneration = new TargetRoleGeneration(keyserverClient)

  def regenerateSignedRoles(repoId: RepoId): Future[SignedPayload[Json]] = {
    async {
      await(fetchAndCacheRootRole(repoId))

      val expireAt = defaultExpire

      val targetVersion = await(nextVersion(repoId, RoleType.TARGETS))
      val targetRole = await(targetRoleGeneration.generate(repoId, expireAt, targetVersion))
      val signedTarget = await(signRole(repoId, RoleType.TARGETS, targetRole))

      val dependent = await(regenerateSignedDependent(repoId, signedTarget, expireAt))

      await(signedRoleRepo.persistAll(signedTarget :: dependent))

      signedTarget.content
    }
  }

  def regenerateSignedDependent(repoId: RepoId, targetRole: SignedRole, expireAt: Instant): Future[List[SignedRole]] = async {
    val signedRoot = await(fetchAndCacheRootRole(repoId))

    val snapshotVersion = await(nextVersion(repoId, RoleType.SNAPSHOT))
    val snapshotRole = genSnapshotRole(signedRoot, targetRole, expireAt, snapshotVersion)
    val signedSnapshot = await(signRole(repoId, RoleType.SNAPSHOT, snapshotRole))

    val timestampVersion = await(nextVersion(repoId, RoleType.TIMESTAMP))
    val timestampRole = genTimestampRole(signedSnapshot, expireAt, timestampVersion)
    val signedTimestamp = await(signRole(repoId, RoleType.TIMESTAMP, timestampRole))

    List(signedSnapshot, signedTimestamp)
  }

  def addToTarget(targetItem: TargetItem): Future[SignedPayload[Json]] =
    targetRoleGeneration.addTargetItem(targetItem).flatMap(_ ⇒ regenerateSignedRoles(targetItem.repoId))

  def signRole[T <: VersionedRole : Decoder : Encoder](repoId: RepoId, roleType: RoleType, role: T): Future[SignedRole] = {
    keyserverClient.sign(repoId, roleType, role).map { signedRole =>
      SignedRole.withChecksum(repoId, roleType, signedRole, role.version, role.expires)
    }
  }

  def updateCacheRootRole(repoId: RepoId): Future[SignedRole] =
    keyserverClient.fetchRootRole(repoId).flatMap { rootRole =>
      val signedRoot = SignedRole.withChecksum(repoId, RoleType.ROOT, rootRole, rootRole.signed.version, rootRole.signed.expires)
      signedRoleRepo.persist(signedRoot)
  }

  private def fetchAndCacheRootRole(repoId: RepoId): Future[SignedRole] = {
    signedRoleRepo.find(repoId, RoleType.ROOT).recoverWith {
      case SignedRoleNotFound => updateCacheRootRole(repoId)
    }
  }

  private def findAndCacheRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    signedRoleRepo
      .find(repoId, roleType)
      .recoverWith { case SignedRoleNotFound => generateAndCacheRole(repoId, roleType) }
  }

  private def findFreshTimestamp(repoId: RepoId): Future[SignedRole] = {
    signedRoleRepo.find(repoId, RoleType.TIMESTAMP).flatMap { role =>
      if(role.expireAt.isBefore(Instant.now.plus(1, ChronoUnit.HOURS))) {
        val timestampRole = role.content.signed.as[TimestampRole].valueOr(throw _)
        val nextVersion = timestampRole.version + 1
        val nextExpires = timestampRole.expires.plus(1, ChronoUnit.DAYS)
        val newRole = timestampRole.copy(expires = nextExpires, version = nextVersion)

        signRole(repoId, RoleType.TIMESTAMP, newRole).flatMap(signedRoleRepo.persist)
      } else {
        FastFuture.successful(role)
      }
    }.recoverWith {
      case SignedRoleRepository.SignedRoleNotFound =>
        generateAndCacheRole(repoId, RoleType.TIMESTAMP)
    }
  }

  private def generateAndCacheRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    regenerateSignedRoles(repoId)
      .recoverWith { case err => log.warn("Could not generate signed roles", err) ; FastFuture.failed(SignedRoleNotFound) }
      .flatMap(_ => signedRoleRepo.find(repoId, roleType))
  }


  def findRole(repoId: RepoId, roleType: RoleType): Future[SignedRole] = {
    roleType match {
      case RoleType.ROOT =>
        fetchAndCacheRootRole(repoId)
      case RoleType.TIMESTAMP =>
        findFreshTimestamp(repoId)
      case _ =>
        findAndCacheRole(repoId, roleType)
    }
  }

  private def nextVersion(repoId: RepoId, roleType: RoleType): Future[Int] =
    signedRoleRepo
      .find(repoId, roleType)
      .map { signedRole =>
        signedRole
          .content
          .signed
          .hcursor
          .downField("version")
          .as[Int]
          .getOrElse(0) + 1
      }
      .recover {
        case SignedRoleNotFound => 1
      }

  private def genSnapshotRole(root: SignedRole, target: SignedRole, expireAt: Instant, version: Int): SnapshotRole = {
    val meta = List(root.asMetaRole, target.asMetaRole).toMap
    SnapshotRole(meta, expireAt, version)
  }

  private def genTimestampRole(snapshotRole: SignedRole, expireAt: Instant, version: Int): TimestampRole = {
    val meta = Map(snapshotRole.asMetaRole)
    TimestampRole(meta, expireAt, version)
  }

  private def defaultExpire: Instant =
    Instant.now().plus(31, ChronoUnit.DAYS)
}

protected class TargetRoleGeneration(roleSigningClient: KeyserverClient)
                          (implicit val db: Database, val ec: ExecutionContext)
  extends TargetItemRepositorySupport {

  def addTargetItem(targetItem: TargetItem): Future[TargetItem] =
    targetItemRepo.persist(targetItem)

  def generate(repoId: RepoId, expireAt: Instant, version: Int): Future[TargetsRole] = {
    targetItemRepo.findFor(repoId).map { targetItems =>
      val targets = targetItems.map { item =>
        val hashes = Map(item.checksum.method -> item.checksum.hash)
        item.filename -> ClientTargetItem(hashes, item.length, item.custom.map(_.asJson))
      }.toMap

      TargetsRole(expireAt, targets, version)
    }
  }
}

