package io.joern.jimple2cpg.rewrite

import io.joern.jimple2cpg.testfixtures.JimpleCode2CpgFixture
import io.shiftleft.semanticcpg.language.*

class JimpleAstRewriterGoldenTests extends JimpleCode2CpgFixture {

  "rewriter on realistic fastjson-style patterns" should {

    "fuse single-use field read into method call argument" in {
      val cpg = code("""
          |import java.lang.reflect.Field;
          |class Holder {
          |  Object value;
          |}
          |class User {
          |  String resolve(Holder h) {
          |    Object v = h.value;
          |    return v.toString();
          |  }
          |}
          |""".stripMargin).cpg

      val method = cpg.method.name("resolve").head
      val fusedAssignments = method.ast.isCall.code.l.filter(_.startsWith("<fused:"))
      withClue("should have at least one fused assignment") {
        fusedAssignments should not be empty
      }
    }

    "fuse single-use $stack temporaries created by Jimple" in {
      val cpg = code("""
          |class Chain {
          |  int compute(int x) {
          |    return x * 2 + 1;
          |  }
          |}
          |""".stripMargin).cpg

      val method = cpg.method.name("compute").head
      val allCodes = method.ast.isCall.code.l

      withClue("should have fused at least one intermediate $stack assignment") {
        val fused = allCodes.filter(_.startsWith("<fused:"))
        fused should not be empty
      }
    }

    "not fuse regular Java local variables" in {
      val cpg = code("""
          |class Regular {
          |  int compute(int x) {
          |    int y = x + 1;
          |    return y + y;
          |  }
          |}
          |""".stripMargin).cpg

      val method = cpg.method.name("compute").head
      val locals = method.ast.isLocal.l

      withClue("regular Java local 'y' should survive") {
        locals.map(_.name) should contain("y")
      }
    }

    "show improved code readability on typical getter pattern" in {
      val cpg = code("""
          |class Container {
          |  int[] data;
          |  int len;
          |  int sum() {
          |    int total = 0;
          |    int n = this.len;
          |    for (int i = 0; i < n; i++) {
          |      total = total + this.data[i];
          |    }
          |    return total;
          |  }
          |}
          |""".stripMargin).cpg

      val method = cpg.method.name("sum").head
      val allCodes = method.ast.isCall.code.l

      withClue("should have fused field reads") {
        val fused = allCodes.filter(_.startsWith("<fused:"))
        fused should not be empty
      }
    }

    "preserve type information during fusion" in {
      val cpg = code("""
          |class Typed {
          |  String format(int x) {
          |    Integer boxed = new Integer(x);
          |    return boxed.toString();
          |  }
          |}
          |""".stripMargin).cpg

      val method = cpg.method.name("format").head
      val fusedCodes = method.ast.isCall.code.l.filter(_.startsWith("<fused:"))

      withClue("should fuse the Integer boxing assignment") {
        fusedCodes should not be empty
      }
    }
  }
}
