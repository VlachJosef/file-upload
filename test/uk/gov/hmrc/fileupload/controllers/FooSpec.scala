package uk.gov.hmrc.fileupload.controllers

import java.io.File

import org.scalatestplus.play.OneAppPerSuite
import play.api
import play.api.ApplicationLoader.Context
import play.api.{Application, ApplicationLoader, Environment, Mode}
import uk.gov.hmrc.fileupload.ApplicationModule
import uk.gov.hmrc.play.test.UnitSpec

class FooSpec extends UnitSpec with OneAppPerSuite {

  override implicit lazy val app: api.Application = {
    val appLoader = new ApplicationLoader {
      override def load(context: Context): Application = new ApplicationModule(context).application
    }
    val context = ApplicationLoader.createContext(
      new Environment(new File("."), ApplicationLoader.getClass.getClassLoader, Mode.Test)
    )
    appLoader.load(context)
  }
}
