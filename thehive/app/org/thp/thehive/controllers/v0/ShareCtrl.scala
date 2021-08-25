package org.thp.thehive.controllers.v0

import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.traversal.Graph
import org.thp.scalligraph.{AuthorizationError, BadRequestError, EntityIdOrName, RichSeq}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.InputShare
import org.thp.thehive.models.Permissions
import org.thp.thehive.services._
import play.api.mvc.{Action, AnyContent, Results}

import scala.util.{Failure, Success, Try}

class ShareCtrl(
    entrypoint: Entrypoint,
    shareSrv: ShareSrv,
    organisationSrv: OrganisationSrv,
    caseSrv: CaseSrv,
    taskSrv: TaskSrv,
    observableSrv: ObservableSrv,
    profileSrv: ProfileSrv,
    implicit val db: Database
) extends TheHiveOpsNoDeps {

  def shareCase(caseId: String): Action[AnyContent] =
    entrypoint("create case shares")
      .extract("shares", FieldsParser[InputShare].sequence.on("shares"))
      .authTransaction(db) { implicit request => implicit graph =>
        val inputShares: Seq[InputShare] = request.body("shares")
        caseSrv
          .get(EntityIdOrName(caseId))
          .can(Permissions.manageShare)
          .getOrFail("Case")
          .flatMap { `case` =>
            inputShares.toTry { inputShare =>
              for {
                organisation <-
                  organisationSrv
                    .current
                    .visibleOrganisations
                    .get(EntityIdOrName(inputShare.organisation.value))
                    .getOrFail("Organisation")
                share     <- shareSrv.shareCase(owner = false, `case`, organisation, inputShare.toSharingProfile)
                richShare <- shareSrv.get(share).richShare.getOrFail("Share")
              } yield richShare
            }
          }
          .map(shares => Results.Ok(shares.toJson))
      }

  def removeShare(shareId: String): Action[AnyContent] =
    entrypoint("remove share")
      .authTransaction(db) { implicit request => implicit graph =>
        doRemoveShare(EntityIdOrName(shareId)).map(_ => Results.NoContent)
      }

  def removeShares(): Action[AnyContent] =
    entrypoint("remove share")
      .extract("shares", FieldsParser[String].sequence.on("ids"))
      .authTransaction(db) { implicit request => implicit graph =>
        val shareIds: Seq[String] = request.body("shares")
        shareIds.map(EntityIdOrName.apply).toTry(doRemoveShare(_)).map(_ => Results.NoContent)
      }

  def removeShares(caseId: String): Action[AnyContent] =
    entrypoint("remove share")
      .extract("organisations", FieldsParser[String].sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisations: Seq[String] = request.body("organisations")

        val organisationIds = organisations.map(o => organisationSrv.getId(EntityIdOrName(o)))
        if (organisationIds.contains(organisationSrv.currentId))
          Failure(BadRequestError("You cannot remove your own share"))
        else
          caseSrv
            .get(EntityIdOrName(caseId))
            .can(Permissions.manageShare)
            .shares
            .filter(_.organisation.getByIds(organisationIds: _*))
            .has(_.owner, false)
            ._id
            .toSeq
            .toTry(shareSrv.unshareCase)
            .map(_ => Results.NoContent)
      }

  def removeTaskShares(taskId: String): Action[AnyContent] =
    entrypoint("remove share tasks")
      .extract("organisations", FieldsParser[String].sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisations: Seq[String] = request.body("organisations")

        taskSrv
          .getOrFail(EntityIdOrName(taskId))
          .flatMap { task =>
            organisations.toTry { organisationName =>
              organisationSrv
                .getOrFail(EntityIdOrName(organisationName))
                .flatMap(shareSrv.unshareTask(task, _))
            }
          }
          .map(_ => Results.NoContent)
      }

  def removeObservableShares(observableId: String): Action[AnyContent] =
    entrypoint("remove share observables")
      .extract("organisations", FieldsParser[String].sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisations: Seq[String] = request.body("organisations")

        observableSrv
          .getOrFail(EntityIdOrName(observableId))
          .flatMap { observable =>
            organisations.toTry { organisationName =>
              organisationSrv
                .getOrFail(EntityIdOrName(organisationName))
                .flatMap(shareSrv.unshareObservable(observable, _))
            }
          }
          .map(_ => Results.NoContent)
      }

  private def doRemoveShare(shareId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    if (!shareSrv.get(shareId).`case`.can(Permissions.manageShare).exists)
      Failure(AuthorizationError("You are not authorized to remove share"))
    else if (shareSrv.get(shareId).byOrganisation(authContext.organisation).exists)
      Failure(AuthorizationError("You can't remove your share"))
    else if (shareSrv.get(shareId).has(_.owner, true).exists)
      Failure(AuthorizationError("You can't remove initial shares"))
    else
      shareSrv.unshareCase(shareId)

  def updateShare(shareId: String): Action[AnyContent] =
    entrypoint("update share")
      .extract("profile", FieldsParser.string.on("profile"))
      .authTransaction(db) { implicit request => implicit graph =>
        val profile: String = request.body("profile")
        if (!shareSrv.get(EntityIdOrName(shareId)).`case`.can(Permissions.manageShare).exists)
          Failure(AuthorizationError("You are not authorized to remove share"))
        for {
          richShare <-
            shareSrv
              .get(EntityIdOrName(shareId))
              .filter(_.organisation.visibleOrganisations.visible)
              .richShare
              .getOrFail("Share")
          profile <- profileSrv.getOrFail(EntityIdOrName(profile))
          _       <- shareSrv.updateProfile(richShare.share, profile)
        } yield Results.Ok
      }

  def listShareCases(caseId: String): Action[AnyContent] =
    entrypoint("list case shares")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val shares = caseSrv
          .get(EntityIdOrName(caseId))
          .can(Permissions.manageShare)
          .shares
          .visible
          .filterNot(_.organisation.current)
          .richShare
          .toSeq

        Success(Results.Ok(shares.toJson))
      }

  def listShareTasks(caseId: String, taskId: String): Action[AnyContent] =
    entrypoint("list task shares")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val shares = taskSrv
          .get(EntityIdOrName(taskId))
          .shares
          .filter(_.`case`.get(EntityIdOrName(caseId)).can(Permissions.manageShare))
          .filterNot(_.organisation.current)
          .richShare
          .toSeq

        Success(Results.Ok(shares.toJson))
      }

  def listShareObservables(caseId: String, observableId: String): Action[AnyContent] =
    entrypoint("list observable shares")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val shares = observableSrv
          .get(EntityIdOrName(observableId))
          .shares
          .filter(_.`case`.get(EntityIdOrName(caseId)).can(Permissions.manageShare))
          .filterNot(_.organisation.current)
          .richShare
          .toSeq

        Success(Results.Ok(shares.toJson))
      }

  def shareTask(taskId: String): Action[AnyContent] =
    entrypoint("share task")
      .extract("organisations", FieldsParser.string.sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisationIds: Seq[String] = request.body("organisations")

        for {
          task          <- taskSrv.getOrFail(EntityIdOrName(taskId))
          _             <- taskSrv.get(task).`case`.can(Permissions.manageShare).existsOrFail
          organisations <- organisationIds.map(EntityIdOrName(_)).toTry(organisationSrv.get(_).visible.getOrFail("Organisation"))
          _             <- shareSrv.shareTask(task, organisations)
        } yield Results.NoContent
      }

  def shareObservable(observableId: String): Action[AnyContent] =
    entrypoint("share observable")
      .extract("organisations", FieldsParser.string.sequence.on("organisations"))
      .authTransaction(db) { implicit request => implicit graph =>
        val organisationIds: Seq[String] = request.body("organisations")
        for {
          observable    <- observableSrv.getOrFail(EntityIdOrName(observableId))
          _             <- observableSrv.get(observable).`case`.can(Permissions.manageShare).existsOrFail
          organisations <- organisationIds.map(EntityIdOrName(_)).toTry(organisationSrv.get(_).visible.getOrFail("Organisation"))
          _             <- shareSrv.shareObservable(observable, organisations)
        } yield Results.NoContent
      }

}
