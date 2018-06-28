name := "nct-link"

organization := "edu.utdallas.hltri"

version := "1.0-SNAPSHOT"

description := "Tool for Retrieving Medline Articles discussing Clinical Trials"

// enable publishing to maven
publishMavenStyle := true

// do not append scala version to the generated artifacts
crossPaths := false

// do not add scala libraries as a dependency!
autoScalaLibrary := false

// tell SBT to shut up unless its important
logLevel := Level.Warn

fork in run := true

javaOptions in run ++= Seq("-ea", "-Xmx14g", "-server", "-XX:+CMSClassUnloadingEnabled", "-XX:+UseG1GC")

connectInput in run :=   true // Connects stdin to sbt during forked runs

outputStrategy in run :=  Some(StdoutOutput) // Get rid of output prefix

// Java date parsing library
libraryDependencies += "com.joestelmach" % "natty" % "0.13"

// Google's general utility library
libraryDependencies += "com.google.guava" % "guava" % "21.+"
libraryDependencies += "com.google.errorprone" % "error_prone_annotations" % "2.0.15" % "provided"

// jUnit testing framework
libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test"

lazy val `trec-pm` = RootProject(file("../trec-pm"))

lazy val util = RootProject(file("../hltri-util"))

lazy val inquire = RootProject(file("../inquire"))

lazy val `nct-link` = project in file (".") dependsOn(util, inquire, `trec-pm`)
