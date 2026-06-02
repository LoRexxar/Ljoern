package io.joern.javasrc2cpg.querying

import io.joern.javasrc2cpg.testfixtures.JavaSrcCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class CanonicalNameTests extends JavaSrcCode2CpgFixture {
  "javasrc2cpg" should {
    "keep dot-style fullNames for nested types and match canonical signature format" in {
      val cpg = code(
        """class Outer {
          |  class Inner {
          |    void m() {}
          |  }
          |}
          |""".stripMargin,
        "Outer.java"
      )
      cpg.method.nameExact("m").fullName.head shouldBe "Outer.Inner.m:void()"
      cpg.method.nameExact("m").signature.head shouldBe "void()"
    }
  }
}

