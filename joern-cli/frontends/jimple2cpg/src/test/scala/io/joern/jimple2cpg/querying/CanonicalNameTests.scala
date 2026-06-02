package io.joern.jimple2cpg.querying

import io.joern.jimple2cpg.testfixtures.JimpleCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class CanonicalNameTests extends JimpleCode2CpgFixture {
  "jimple2cpg" should {
    "normalize inner class separator in METHOD.fullName" in {
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
    }
  }
}

