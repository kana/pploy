package models

import java.io.File
import org.eclipse.jgit.api._
import org.eclipse.jgit.transport.URIish
import play.api.Logger
import scala.collection.JavaConversions._
import scala.sys.process.{ProcessLogger, Process}

case class Repo(name: String) {
  lazy val dir = WorkingDir.projectDir(name)
  lazy val git = Git.open(dir)

  private def gitProc(args: String*) = {
    Logger.info("git " + args.mkString(" "))
    Process("git" +: args, dir)
  }

  def checkout(ref: String): Unit = {
    // if checkout_overwrite script exists
    val file = new File(dir, ".deploy/bin/checkout_overwrite")

    val proc = if (file.isFile) {
      Process(
        Seq("bash", "-c", file.getCanonicalPath + " 2>&1"),
        dir,
        "DEPLOY_COMMIT" -> ref
      )
    } else {
      gitProc("fetch", "--prune") #&&
        gitProc("reset", "--hard", ref) #&&
        gitProc("clean", "-fdx") #&&
        gitProc("submodule", "sync") #&&
        gitProc("submodule", "init") #&&
        gitProc("submodule", "update", "--recursive")
    }

    val result = proc.run(ProcessLogger(
      line => { Logger.info("stdout: " + line) },
      line => { Logger.info("stderr: " + line) }
    ))

    if (result.exitValue() != 0) throw new RuntimeException("checkout failed")
  }

  def commits = {
    val commits = git.log.setMaxCount(20).call()
    commits.map { new Commit(git, _) }
  }
}

object Repo {
  def clone(url: String): Repo = {
    val uriish = new URIish(url)
    Process(Seq("git", "clone", url), WorkingDir.projectsDir).!
    Repo(uriish.getHumanishName)
  }
}
