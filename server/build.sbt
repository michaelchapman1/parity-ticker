name := "parity-ticker"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.11.7"

val parityVersion = "0.7.0"

libraryDependencies ++= Seq(
  "com.paritytrading.nassau"     % "nassau-util"   % "0.13.0",
  "com.paritytrading.parity"     % "parity-book"   % parityVersion,
  "com.paritytrading.parity"     % "parity-net"    % parityVersion,
  "com.paritytrading.parity"     % "parity-util"   % parityVersion,
  "org.jvirtanen.config"         % "config-extras" % "0.2.0"
)

lazy val root = (project in file(".")).enablePlugins(PlayScala)
