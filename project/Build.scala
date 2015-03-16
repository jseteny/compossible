import sbt._
import Keys._

object MyBuild extends Build{
  val repoKind = SettingKey[String]("repo-kind", "Maven repository kind (\"snapshots\" or \"releases\")")

  lazy val aRootProject = Project(id = "compossible", base = file("."),
    settings = Seq(
      name := "compossible",
      scalaVersion := "2.11.5",
      description := "Composable Records and type-indexed Maps for Scala",
      libraryDependencies ++= Seq(
        "org.cvogt" %% "scala-extensions" % "0.3-SNAPSHOT",
        "org.scalatest" %% "scalatest" % "2.2.4" % "test",
        "com.typesafe.play" %% "play-json" % "2.4.0-M1"
      ),
      libraryDependencies += "com.lihaoyi" %% "ammonite-repl" % "0.2.7" % "test",
      initialCommands in console := "ammonite.repl.Repl.main(null)",
      resolvers ++= Seq(
        Resolver.sonatypeRepo("releases"),
        Resolver.sonatypeRepo("snapshots")
      ),
      libraryDependencies <+= scalaVersion(
        "org.scala-lang" % "scala-reflect" % _ //% "optional"
      ),
      scalacOptions ++= Seq(
        "-feature", "-deprecation", "-unchecked",
        "-language:implicitConversions",
        "-language:experimental.macros",
        "-language:postfixOps",
        "-language:dynamics"
      ),
      //scalacOptions ++= Seq("-Xprint:typer"),
      //testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oFD"),
      parallelExecution := false, // <- until TMap thread-safety issues are resolved
      version := "0.2-SNAPSHOT",
      organizationName := "Jan Christopher Vogt",
      organization := "org.cvogt",
      scalacOptions in (Compile, doc) <++= (version,sourceDirectory in Compile,name).map((v,src,n) => Seq(
        "-doc-title", n,
        "-doc-version", v,
        "-doc-footer", "Compossible is developed by Jan Christopher Vogt.",
        "-sourcepath", src.getPath, // needed for scaladoc to strip the location of the linked source path
        "-doc-source-url", "https://github.com/cvogt/compossible/blob/"+v+"/src/main€{FILE_PATH}.scala",
        "-implicits",
        "-diagrams", // requires graphviz
        "-groups"
      )),
      repoKind <<= (version)(v => if(v.trim.endsWith("SNAPSHOT")) "snapshots" else "releases"),
      //publishTo <<= (repoKind)(r => Some(Resolver.file("test", file("c:/temp/repo/"+r)))),
      publishTo <<= (repoKind){
        case "snapshots" => Some("snapshots" at "https://oss.sonatype.org/content/repositories/snapshots")
        case "releases" =>  Some("releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2")
      },
      publishMavenStyle := true,
      publishArtifact in Test := false,
      pomIncludeRepository := { _ => false },
      makePomConfiguration ~= { _.copy(configurations = Some(Seq(Compile, Runtime, Optional))) },
      licenses += ("Creative Commons Attribution-ShareAlike 4.0 International", url("https://creativecommons.org/licenses/by-sa/4.0/")),
      homepage := Some(url("http://github.com/cvogt/compossible")),
      startYear := Some(2015),
      pomExtra :=
        <developers>
          <developer>
            <id>cvogt</id>
            <name>Jan Christopher Vogt</name>
            <timezone>-5</timezone>
            <url>https://github.com/cvogt/</url>
          </developer>
        </developers>
          <scm>
            <url>git@github.com:cvogt/compossible.git</url>
            <connection>scm:git:git@github.com:cvogt/compossible.git</connection>
          </scm>
    )
  )
}
