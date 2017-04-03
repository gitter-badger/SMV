name := "_ARTIFACT_ID_"

organization := "_GROUP_ID_"

version := "1.0-SNAPSHOT"

scalaVersion := "2.10.4"

scalacOptions ++= Seq("-deprecation", "-feature")

val sparkVersion = "1.5.2"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql"  % sparkVersion % "provided",
  "org.apache.spark" %% "spark-hive" % sparkVersion % "provided",
  "org.tresamigos" %% "smv" % "1.5-SNAPSHOT",
  "org.scalatest" %% "scalatest" % "2.2.0" % "test"
)

parallelExecution in Test := false

mainClass in assembly := Some("org.tresamigos.smv.SmvApp")

assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)

assemblyJarName in assembly := s"${name.value}-${version.value}-jar-with-dependencies.jar"

// allow Ctrl-C to interrupt long-running tasks without exiting sbt,
// if the task implementation correctly handles the signal
cancelable in Global := true

val smvInit = if (sys.props.contains("smvInit")) {
    val files = sys.props.get("smvInit").get.split(",")
    files.map{f=> IO.read(new File(f))}.mkString("\n")
  } else ""

initialCommands in console := s"""
val sc = new org.apache.spark.SparkContext("local", "shell")
val sqlContext = new org.apache.spark.sql.SQLContext(sc)
${smvInit}
"""

// clean up spark context
cleanupCommands in console := "sc.stop"

// Uncomment the following to include python scripts in the fat jar
// unmanagedResourceDirectories in Compile += (sourceDirectory in Compile).value / "python"

// graphviz-java depends on both xml-apis and batik-ext, both of which
// contain org.w3c.dom.events classes
assemblyMergeStrategy in assembly := {
  case PathList("org", "w3c", "dom", "events", xs @ _*) => MergeStrategy.last
  case x =>
    val oldStrat = (assemblyMergeStrategy in assembly).value
    oldStrat(x)
}
