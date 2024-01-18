import ProjectKeys._
import Implicits._

val scala3Version = "3.3.1"

ThisBuild / tlBaseVersion := "1.0"

ThisBuild / projectName      := "cdp-scala"
ThisBuild / organization     := "io.github.windymelt"
ThisBuild / organizationName := "cdp.scala authors"
ThisBuild / startYear        := Some(2024)
ThisBuild / licenses         := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("windymelt", "Windymelt"),
)

ThisBuild / scalaVersion       := scala3Version
ThisBuild / crossScalaVersions := Seq(scala3Version)

lazy val compileSettings = Def.settings(
  tlFatalWarnings := true,

  scalacOptions --= Seq(
    // Scala 3.0.1以降だとうまく動かない
    // https://github.com/lampepfl/dotty/issues/14952
    "-Ykind-projector:underscores",
  ),
  Test / scalacOptions --= Seq(
    // テストだとちょっと厳しすぎる
    "-Wunused:locals",
  ),
  Compile / console / scalacOptions --= Seq(
    // コンソールで import した瞬間はまだ使ってないから当然許したい
    "-Wunused:imports",
  ),
)

lazy val root = tlCrossRootProject
  .aggregate(core)
  .settings(compileSettings)
  .settings(
    console        := (core.jvm / Compile / console).value,
    Test / console := (core.jvm / Test / console).value,
  )

lazy val core = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .withoutSuffixFor(JVMPlatform)
  .asModuleWithoutSuffix
  .settings(compileSettings)
  .settings(
    description := "Chrome DevTools Protocol wrapper for Scala",
  )

ThisBuild / githubWorkflowJavaVersions := Seq(
  // No support for 8 because JDK Http Client comes from 11
  JavaSpec.temurin("11"),
  JavaSpec.temurin("17"),
)

ThisBuild / githubWorkflowTargetBranches := Seq("main")

ThisBuild / tlCiReleaseBranches          := Seq()

//
// Document
//

lazy val docs = project
  .in(file("site"))
  .dependsOn(core.jvm)
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    scalacOptions --= Seq(
      // ドキュメントの例ではローカル変数を使わないこともあるだろう
      "-Wunused:locals",
    ),
  )

import laika.helium.Helium

import laika.helium.config._
import laika.ast.Path.Root

docs / laikaTheme := Helium.defaults.site
  .topNavigationBar(
    homeLink = IconLink.internal(Root / "index.md", HeliumIcon.home),
    navLinks = Seq(
    ),
    versionMenu = VersionMenu.create(
      versionedLabelPrefix = "Version:",
      unversionedLabel = "Choose Version"
    ),
    highContrast = true
  ).build
