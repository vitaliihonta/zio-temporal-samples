package dev.vhonta.content.processor.job.processor

import com.typesafe.config.{Config, ConfigFactory}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.postgresql.ds.PGSimpleDataSource
import java.util.Properties
import scala.jdk.CollectionConverters._

object JdbcDataSources {
  def load(configPath: String): HikariDataSource = {
    val config = ConfigFactory.load()
    val hikariConfig = {
      val p = new Properties
      for (entry <- config.getConfig(s"$configPath.hikari").entrySet.asScala) {
        p.setProperty(entry.getKey, entry.getValue.unwrapped.toString)
      }
      p.put("dataSource", postgresSource(config, configPath))
      new HikariConfig(p)
    }

    new HikariDataSource(hikariConfig)
  }

  private def postgresSource(config: Config, configPath: String): PGSimpleDataSource = {
    val pg = new PGSimpleDataSource()
    pg.setServerNames(Array(config.getString(s"$configPath.serverName")))
    pg.setPortNumbers(Array(config.getInt(s"$configPath.portNumber")))
    pg.setUser(config.getString(s"$configPath.username"))
    pg.setPassword(config.getString(s"$configPath.password"))
    pg.setDatabaseName(config.getString(s"$configPath.databaseName"))
    pg
  }
}
