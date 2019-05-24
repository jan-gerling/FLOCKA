package org.flocka.ServiceBasics

import com.typesafe.config.{Config, ConfigFactory}

object Configs {
  val conf: Config = ConfigFactory.load()
}
