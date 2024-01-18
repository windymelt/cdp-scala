import sbt.settingKey

object ProjectKeys {
  lazy val projectName = settingKey[String]("project name")
}
