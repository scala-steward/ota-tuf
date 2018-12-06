package com.advancedtelematic.tuf.reposerver.http

import java.time.Instant
import java.time.temporal.ChronoUnit

import cats.syntax.option._
import akka.http.scaladsl.model.StatusCodes
import cats.syntax.show._
import com.advancedtelematic.libats.data.ErrorRepresentation
import com.advancedtelematic.libats.data.RefinedUtils.RefineTry
import com.advancedtelematic.libtuf.crypt.TufCrypto
import com.advancedtelematic.libtuf.data.ClientCodecs._
import com.advancedtelematic.libtuf.data.ClientDataType.{Delegation, Delegations, TargetsRole, ValidDelegatedPathPattern, ValidDelegatedRoleName}
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{Ed25519KeyType, RepoId, RoleType, SignedPayload, TufKeyPair}
import com.advancedtelematic.tuf.reposerver.util.{RepoResourceSpecUtil, ResourceSpec, TufReposerverSpec}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import org.scalactic.source.Position

class RepoResourceDelegationsSpec extends TufReposerverSpec
  with ResourceSpec
  with RepoResourceSpecUtil {

  lazy val keyPair = Ed25519KeyType.crypto.generateKeyPair()

  val delegatedRoleName = "mydelegation".refineTry[ValidDelegatedRoleName].get

  val delegation = {
    val delegationPath = "mypath/*".refineTry[ValidDelegatedPathPattern].get
    Delegation(delegatedRoleName, List(keyPair.pubkey.id), List(delegationPath))
  }

  val delegations = Delegations(Map(keyPair.pubkey.id -> keyPair.pubkey), List(delegation))

  private def addDelegationToRepo(_delegations: Delegations = delegations)
                                  (implicit repoId: RepoId, pos: Position): Unit = {
    val oldTargets = buildSignedTargetsRole(repoId, Map.empty)
    val newTargets = oldTargets.signed.copy(delegations = _delegations.some)
    val signedTargets = fakeKeyserverClient.sign(repoId, RoleType.TARGETS, newTargets.asJson)

    Put(apiUri(s"repo/${repoId.show}/targets"), signedTargets).withValidTargetsCheckSum ~> routes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  private def buildSignedDelegatedTargets(delegatedKeyPair: TufKeyPair = keyPair)
                                         (implicit repoId: RepoId, pos: Position): SignedPayload[TargetsRole] = {
    val delegationTargets = TargetsRole(Instant.now().plus(30, ChronoUnit.DAYS), targets = Map.empty, version = 2)
    val signature = TufCrypto.signPayload(delegatedKeyPair.privkey, delegationTargets.asJson).toClient(delegatedKeyPair.pubkey.id)
    SignedPayload(List(signature), delegationTargets, delegationTargets.asJson)
  }

  private def pushSignedDelegatedMetadata(signedPayload: SignedPayload[TargetsRole])
                                        (implicit repoId: RepoId): RouteTestResult =
    Put(apiUri(s"repo/${repoId.show}/delegations/${delegatedRoleName.value}.json"), signedPayload) ~> routes

  test("accepts delegated targets") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo()

    Get(apiUri(s"repo/${repoId.show}/targets.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].signed.delegations should contain(delegations)
    }
  }


  test("accepts delegated role metadata when signed with known keys") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo()

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  test("accepts overwrite of existing delegated role metadata") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo()

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.NoContent
    }

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  test("returns delegated role metadata") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo()

    val signedDelegationRole = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata(signedDelegationRole) ~> check {
      status shouldBe StatusCodes.NoContent
    }

    Get(apiUri(s"repo/${repoId.show}/delegations/${delegatedRoleName.value}.json")) ~> routes ~> check {
      status shouldBe StatusCodes.OK
      responseAs[SignedPayload[TargetsRole]].asJson shouldBe signedDelegationRole.asJson
    }
  }

  test("rejects delegated metadata when not defined in targets.json") {
    implicit val repoId = addTargetToRepo()

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe Errors.DelegationNotDefined.code
    }
  }

  test("rejects delegated metadata when not signed according to threshold") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo(delegations.copy(roles = List(delegation.copy(threshold = 2))))

    val signedDelegation = buildSignedDelegatedTargets()

    pushSignedDelegatedMetadata( signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PayloadSignatureInvalid
    }
  }

  test("does not allow repeated signatures to check threshold") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo(delegations.copy(roles = List(delegation.copy(threshold = 2))))

    val default = buildSignedDelegatedTargets()
    val signedDelegation = SignedPayload(default.signatures.head +: default.signatures, default.signed, default.json)

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PayloadSignatureInvalid
    }
  }

  test("rejects delegated metadata when not properly signed") {
    implicit val repoId = addTargetToRepo()

    addDelegationToRepo()

    val otherKey = Ed25519KeyType.crypto.generateKeyPair()
    val delegationTargets = TargetsRole(Instant.now().plus(30, ChronoUnit.DAYS), targets = Map.empty, version = 2)
    val signature = TufCrypto.signPayload(otherKey.privkey, delegationTargets.asJson).toClient(otherKey.pubkey.id)
    val signedDelegation = SignedPayload(List(signature), delegationTargets, delegationTargets.asJson)

    pushSignedDelegatedMetadata(signedDelegation) ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[ErrorRepresentation].code shouldBe ErrorCodes.PayloadSignatureInvalid
    }
  }
}
