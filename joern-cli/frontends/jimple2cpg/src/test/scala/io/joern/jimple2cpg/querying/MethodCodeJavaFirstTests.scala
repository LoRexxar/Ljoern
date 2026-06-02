package io.joern.jimple2cpg.querying

import io.joern.jimple2cpg.Config
import io.joern.jimple2cpg.testfixtures.JimpleCode2CpgFixture
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*

class MethodCodeJavaFirstTests extends JimpleCode2CpgFixture {

  "METHOD.code" should {
    "prefer decompiled Java source when available" in {
      val cpg: Cpg = code("""
          |class Foo {
          |  public static void foo() {
          |    System.out.println("MAGIC_123");
          |  }
          |}
          |""".stripMargin)
        .withConfig(Config().withDisableFileContent(false))
        .cpg

      val methodCode = cpg.method.name("foo").code.l.headOption.getOrElse("")
      methodCode should include("System.out.println")
      methodCode should include("MAGIC_123")
      methodCode should not include ("$stack")
    }
  }
}
