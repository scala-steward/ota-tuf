libraryDependencies ++= {
  Seq(
    "org.flywaydb" % "flyway-core" % "6.0.8",
    "com.amazonaws" % "aws-java-sdk-s3" % "1.11.338"
  )
}

mainClass in Compile := Some("com.advancedtelematic.tuf.reposerver.Boot")

fork := true
