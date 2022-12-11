/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import ReleaseTransformations._
import PgpKeys._
import sbt.complete.Parsers.spaceDelimited

name := "s2graph"

lazy val commonSettings = Seq(
  organization := "org.apache.s2graph",
  scalaVersion := "2.11.8",
  isSnapshot := version.value.endsWith("-SNAPSHOT"),
  scalacOptions := Seq("-language:postfixOps", "-unchecked", "-deprecation", "-feature", "-Xlint", "-Xlint:-missing-interpolator", "-target:jvm-1.8"),
  javaOptions ++= collection.JavaConversions.propertiesAsScalaMap(System.getProperties).map { case (key, value) => "-D" + key + "=" + value }.toSeq,
  testOptions in Test += Tests.Argument("-oDF"),
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
  parallelExecution in Test := false,
  libraryDependencies ++= Common.loggingRuntime,
  unmanagedClasspath in Runtime <+= (resourceDirectory in Compile).map(Attributed.blank),
  resolvers += Resolver.mavenLocal
) ++ Publisher.defaultSettings

Revolver.settings

lazy val s2http = project
  .dependsOn(s2core, s2graphql)
  .settings(commonSettings: _*)
  .settings(dockerSettings: _*)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

lazy val s2graphql = project
  .dependsOn(s2core)
  .settings(commonSettings: _*)
  .settings(dockerSettings: _*)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

lazy val s2core = project.settings(commonSettings: _*)

lazy val spark = project.settings(commonSettings: _*)

//lazy val loader = project.dependsOn(s2core, spark)
//  .settings(commonSettings: _*)

lazy val s2counter_core = project.dependsOn(s2core)
  .settings(commonSettings: _*)

lazy val s2counter_loader = project.dependsOn(s2counter_core, spark)
  .settings(commonSettings: _*)

lazy val s2jobs = project.dependsOn(s2core)
  .settings(commonSettings: _*)

lazy val s2graph_gremlin = project.dependsOn(s2core)
  .settings(commonSettings: _*)

lazy val root = (project in file("."))
  .aggregate(s2core, s2jobs, s2http)
  .dependsOn(s2http, s2jobs, s2counter_loader, s2graphql) // this enables packaging on the root project
  .settings(commonSettings: _*)

lazy val dockerSettings = Seq(
  dockerfile in docker := {
    val appDir: File = stage.value
    val targetDir = s"/u/apps/${name.value}"

    new Dockerfile {
      from("java:8")
      entryPoint(s"$targetDir/bin/${executableScriptName.value}")
      env("EXTRA_JARS", "")
      env("ZONEINFO", "Asia/Seoul")
      copy(appDir, targetDir)
      workDir(targetDir)
      expose(9000)
    }
  },
  bashScriptExtraDefines ++= Seq(
    s"""cp $$EXTRA_JARS/* /u/apps/${name.value}/lib""",
    "ln -sf /usr/share/zoneinfo/$ZONEINFO /etc/localtime; date"
  ),
  scriptClasspath += "*",
  imageNames in docker := Seq(
    ImageName(s"s2graph/${name.value}:${version.value}")
  ),
  buildOptions in docker := BuildOptions(
    cache = false,
    removeIntermediateContainers = BuildOptions.Remove.Always,
    pullBaseImage = BuildOptions.Pull.Always
  )
)

lazy val runRatTask = inputKey[Unit]("Runs Apache rat on S2Graph")

runRatTask := {
  // pass absolute path for apache-rat jar.
  val args: Seq[String] = spaceDelimited("<arg>").parsed
  s"sh dev/run-rat.sh ${args.head}" !
}

Packager.defaultSettings

publishSigned := {
  (publishSigned in s2http).value
  (publishSigned in s2core).value
  (publishSigned in spark).value
  (publishSigned in s2jobs).value
  (publishSigned in s2counter_core).value
  (publishSigned in s2counter_loader).value
}

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,              // : ReleaseStep
  inquireVersions,                        // : ReleaseStep
  runTest,                                // : ReleaseStep
  setReleaseVersion,                      // : ReleaseStep
  commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
  tagRelease,                             // : ReleaseStep
  publishArtifacts,                       // : ReleaseStep, checks whether `publishTo` is properly set up
  setNextVersion,                         // : ReleaseStep
  commitNextVersion
)

releasePublishArtifactsAction := publishSigned.value

mainClass in Compile := None

parallelExecution in ThisBuild := false
