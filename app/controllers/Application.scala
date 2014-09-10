package controllers

import java.io.File

import play.api._
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc._
import play.api.Play.current
import models._
import playcli.CLI

object Application extends Controller {
  def getCurrentUser(request: Request[AnyContent]) = {
    request.cookies.get("user").map {
      cookie => User(cookie.value)
    }
  }

  val createProjectForm = Form( "url" -> text )

  def index() = Action { implicit request =>
    Ok(views.html.index())
  }

  def create() = Action { implicit request =>
    val url = createProjectForm.bindFromRequest.get
    val proj = Project(Repo.clone(url))
    Redirect("/" + proj.name)
  }

  def project(project: String) = Action { implicit request =>
    val proj = Project(project)
    val user = getCurrentUser(request)

    Ok(views.html.project(proj, user))
  }

  def lockUser(project: String) = Action { implicit request =>
    val proj = Project(project)
    if (proj.isLocked) Ok(proj.lock.user.name)
    else Ok("")
  }

  val lockOperationForm = Form(tuple( "user" -> nonEmptyText, "operation" -> text ))

  def lock(project: String) = Action { implicit request =>
    val proj = Project(project)

    lockOperationForm.bindFromRequest.fold(
      errors => Redirect("/"+proj.name),
      f => {
        val (userName, operation) = f
        val user = User(userName)
        operation match {
          case "gain" => proj.gainLock(user)
          case "release" => proj.releaseLock(user)
          case "extend" => proj.extendLock(user)
          case _ =>
            throw new RuntimeException("operation not specified")
        }
        Redirect("/"+proj.name)
          .withCookies(Cookie("user", userName, Some(3600*24*7)))
      }
    )
  }

  val checkoutForm = Form( "ref" -> nonEmptyText )

  def checkout(project: String) = Action { implicit request =>
    val proj = Project(project)
    val user = getCurrentUser(request) match {
      case None => throw new RuntimeException("user not selected")
      case Some(u) => u
    }
    proj.assertLockedByUser(user)

    checkoutForm.bindFromRequest.fold(
      errors => BadRequest,
      ref => {
        Ok.chunked(CLI.enumerate(proj.checkout(ref)))
          .withHeaders("Content-Type" -> "text/plain", "X-Content-Type-Options" -> "nosniff")
      }
    )
  }

  val deployForm = Form( "target" -> text )

  def deploy(project: String) = Action { implicit request =>
    val proj = Project(project)
    val user = getCurrentUser(request) match {
      case None => throw new RuntimeException("user not selected")
      case Some(u) => u
    }
    proj.assertLockedByUser(user)

    deployForm.bindFromRequest.fold(
      errors => BadRequest,
      target => {
        Ok.chunked(CLI.enumerate(proj.execDeploy(user, target), chunkSize = 256))
          .withHeaders("Content-Type" -> "text/plain", "X-Content-Type-Options" -> "nosniff")
      }
    )
  }

  def commits(project: String) = Action { implicit request =>
    val proj = Project(project)
    Ok(views.html.commits(proj.repo.commits))
  }

  def logs(project: String) = Action { implicit request =>
    val proj = Project(project)
    val file = new File(WorkingDir.logFile(proj.name).toString)
    if (file.isFile) {
      Ok.sendFile(content = file, inline = true)
        .withHeaders("Content-Type" -> "text/plain", "X-Content-Type-Options" -> "nosniff")
    } else {
      NotFound
    }
  }

  def remove(project: String) = Action { implicit request =>
    Project(project).remove()
    Redirect("/")
  }
}