package org.scalafmt.sbt

import org.scalafmt.interfaces.Scalafmt
import sbt.Keys._
import sbt.Def
import sbt._
import complete.DefaultParsers._
import sbt.util.Logger
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import java.nio.file.Path

object ScalafmtPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val scalafmt = taskKey[Unit]("Format Scala sources with scalafmt.")
    /*
    val scalafmtCli: Command =
      Command.args("scalafmtCli", "run the scalafmt command line interface.") {
        case (s, args) =>
          org.scalafmt.cli.Cli.exceptionThrowingMain(
            "--non-interactive" +: args.toArray
          )
          s
      }
      */
    val scalafmtCheck =
      taskKey[Boolean](
        "Fails if a Scala source is mis-formatted. Does not write to files."
      )
    val scalafmtOnCompile =
      settingKey[Boolean](
        "Format Scala source files on compile, off by default."
      )
    val scalafmtConfig = taskKey[Option[File]](
      "Optional location of .scalafmt.conf file. " +
        "If None the default config is used."
    )
    val scalafmtSbt = taskKey[Unit](
      "Format *.sbt and project/*.scala files for this sbt build."
    )
    val scalafmtSbtCheck =
      taskKey[Boolean](
        "Fails if a *.sbt or project/*.scala source is mis-formatted. " +
          "Does not write to files."
      )
    val scalafmtOnly = inputKey[Unit]("Format a single given file.")
  }
  import autoImport._

  private val scalafmtInstance = Scalafmt.create(this.getClass.getClassLoader)

  private val scalafmtDoFormatOnCompile =
    taskKey[Unit]("Format Scala source files if scalafmtOnCompile is on.")

  private val scalaConfig =
    scalafmtConfig.map { c =>
      c.map(_.toPath).getOrElse(
        throw new MessageOnlyException(
          "TODO: ii error message"
        )
      )
    }

  private val sbtConfig = scalaConfig

  private type Input = String
  private type Output = String

  private def withFormattedSources[T](
      sources: Seq[File],
      config: Path
  )(
      onError: (File, Throwable) => T,
      onFormat: (File, Input, Output) => T
  ): Seq[Option[T]] = {
    sources
      .par
      .map { file =>
        val input = IO.read(file)
        val output =
          scalafmtInstance.format(config.toAbsolutePath, file.toPath.toAbsolutePath, input)

        Some(onFormat(file, input, output))
        /*
        output match {
          case Formatted.Failure(e) =>
            if (config.runner.fatalWarnings) {
              throw e
            } else if (config.runner.ignoreWarnings) {
              // do nothing
              None
            } else {
              Some(onError(file, e))
            }
          case Formatted.Success(code) =>
        }
        */
      }.seq
  }

  private def formatSources(
      sources: Seq[File],
      config: Path,
      log: Logger
  ): Unit = {
    val cnt = withFormattedSources(
      sources,
      config
    )(
      (file, e) => {
        log.err(e.toString)
        0
      },
      (file, input, output) => {
        if (input != output) {
          IO.write(file, output)
          1
        } else {
          0
        }
      }
    ).flatten.sum

    if (cnt > 1) {
      log.info(s"Reformatted $cnt Scala sources")
    }

  }

  private def checkSources(
      sources: Seq[File],
      config: Path,
      log: Logger
  ): Boolean = {
    val res = withFormattedSources(sources, config)(
      (file, e) => {
        log.err(e.toString)
        false
      },
      (file, input, output) => {
        val diff = input != output
        if (diff) {
          throw new MessageOnlyException(
            s"${file.toString} isn't formatted properly!"
          )
        }
        !diff
      }
    ).flatten.forall(x => x)
    res
  }

  private lazy val sbtSources = thisProject.map { proj =>
    val rootSbt =
      BuildPaths.configurationSources(proj.base).filterNot(_.isHidden)
    val projectSbt =
      (BuildPaths.projectStandard(proj.base) * GlobFilter("*.sbt")).get
        .filterNot(_.isHidden)
    rootSbt ++ projectSbt
  }
  private lazy val projectSources = thisProject.map { proj =>
    (BuildPaths.projectStandard(proj.base) * GlobFilter("*.scala")).get
  }

  lazy val scalafmtConfigSettings: Seq[Def.Setting[_]] = Seq(
    scalafmt := formatSources(
      (unmanagedSources in scalafmt).value,
      scalaConfig.value,
      streams.value.log
    ),
    scalafmtSbt := {
      formatSources(
        sbtSources.value,
        sbtConfig.value,
        streams.value.log
      )
      formatSources(
        projectSources.value,
        scalaConfig.value,
        streams.value.log
      )
    },
    scalafmtCheck :=
      checkSources(
        (unmanagedSources in scalafmt).value,
        scalaConfig.value,
        streams.value.log
      ),
    scalafmtSbtCheck := {
      checkSources(sbtSources.value, sbtConfig.value, streams.value.log)
      checkSources(projectSources.value, scalaConfig.value, streams.value.log)
    },
    scalafmtDoFormatOnCompile := Def.settingDyn {
      if (scalafmtOnCompile.value) {
        scalafmt in resolvedScoped.value.scope
      } else {
        Def.task(())
      }
    }.value,
    compileInputs in compile := (compileInputs in compile)
      .dependsOn(scalafmtDoFormatOnCompile)
      .value,
    scalafmtOnly := {
      val files = spaceDelimited("<files>").parsed
      val absFiles = files.flatMap(fileS => {
        Try { IO.resolve(baseDirectory.value, new File(fileS)) } match {
          case Failure(e) =>
            streams.value.log.error(s"Error with $fileS file: $e")
            None
          case Success(file) => Some(file)
        }
      })
      formatSources(absFiles, scalaConfig.value, streams.value.log)
    }
  )

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(Compile, Test).flatMap(inConfig(_)(scalafmtConfigSettings))

  override def buildSettings: Seq[Def.Setting[_]] = Seq(
    scalafmtConfig := {
      val path = (baseDirectory in ThisBuild).value / ".scalafmt.conf"
      if (path.exists()) {
        Some(path)
      } else {
        None
      }
    }
  )

  override def globalSettings: Seq[Def.Setting[_]] =
    Seq(
      scalafmtOnCompile := false,
      // commands += autoImport.scalafmtCli
    )
  /*
  ++
      addCommandAlias("scalafmtCliTest", "scalafmtCli --test") ++
      addCommandAlias("scalafmtCliDiffTest", "scalafmtCli --diff --test") ++
      addCommandAlias("scalafmtCliDiff", "scalafmtCli --diff")
      */
}
