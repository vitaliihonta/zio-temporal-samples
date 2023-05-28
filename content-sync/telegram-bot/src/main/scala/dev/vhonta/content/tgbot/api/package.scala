package dev.vhonta.content.tgbot

import io.circe.generic.extras.Configuration

package object api {
  implicit val circeConfiguration: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withSnakeCaseConstructorNames
}
