package io.joern.pysrc2cpg.cpg

import io.shiftleft.semanticcpg.language.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import java.io.File

class MethodCpgTests extends PySrc2CpgFixture with Matchers {
  "A method" should {
    val path = Seq("a", "b.py").mkString(File.separator)

    val cpg = code(
      """
        |def method():
        |   pass
        |""".stripMargin,
      path
    )

    "test method full name" in {
      val method = cpg.method.name("method").head
      method.fullName shouldBe "a.b.method"
    }
  }

  "test method redefinition" in {
    val path = Seq("a", "b.py").mkString(File.separator)
    val cpg = code(
      """
        |class Foo():
        |  def method():
        |    pass
        |  def method():
        |    pass
        |  def method():
        |    pass
        |""".stripMargin,
      path
    )

    cpg.method.name("method").map(m => (m.name, m.fullName)).l should contain theSameElementsAs (List(
      ("method", "a.b.Foo.method"),
      ("method", "a.b.Foo.method$redefinition1"),
      ("method", "a.b.Foo.method$redefinition2")
    ))

    cpg.typeDecl.name("Foo").member.name("method").dynamicTypeHintFullName.l should contain theSameElementsAs (
      List("a.b.Foo.method$redefinition2")
    )

    cpg.typeDecl.name("Foo<meta>").member.name("method").dynamicTypeHintFullName.l should contain theSameElementsAs (
      List("a.b.Foo.method<metaClassAdapter>")
    )
  }

}
