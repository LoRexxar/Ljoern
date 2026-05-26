package io.joern.pysrc2cpg.cpg

import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.semanticcpg.language.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture

class CompareCpgTests extends PySrc2CpgFixture with Matchers {
  "single operation comparison" should {
    val cpg = code("""x < y""".stripMargin)

    "test compare node" in {
      val callNode = cpg.call.code("x < y").head
      callNode.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
      callNode.name shouldBe Operators.lessThan
      callNode.methodFullName shouldBe Operators.lessThan
      callNode.lineNumber shouldBe Some(1)
    }

    "test compare node ast children" in {
      cpg.call.code("x < y").astChildren.order(1).isIdentifier.head.code shouldBe "x"
      cpg.call.code("x < y").astChildren.order(2).isIdentifier.head.code shouldBe "y"
    }

    "test compare node arguments" in {
      cpg.call.code("x < y").argument.argumentIndex(1).isIdentifier.head.code shouldBe "x"
      cpg.call.code("x < y").argument.argumentIndex(2).isIdentifier.head.code shouldBe "y"
    }
  }

  "multi operation comparison" should {
    val cpg = code("""x < y < z""".stripMargin)

    "test compare node" in {
      val andNode = cpg.call.nameExact(Operators.logicalAnd).head
      andNode.astChildren.order(1).isCall.code.head shouldBe "x < y"
      andNode.astChildren.order(2).isBlock.astChildren.isCall.code.head shouldBe "y < z"
    }

  }

}
