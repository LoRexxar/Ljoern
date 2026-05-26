package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.codepropertygraph.generated.EvaluationStrategies
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class AlignmentPhaseATests extends PySrc2CpgFixture(withPostProcessing = false) with Matchers {
  "Phase A alignment" should {
    "write META_DATA.root as absolute path" in {
      val cpg  = code("pass", "test.py")
      val root = cpg.metaData.root.headOption.getOrElse("<empty>")
      root shouldBe Paths.get(root).toAbsolutePath.normalize.toString
    }

    "ensure TYPE node ANY exists via TypeNodePass" in {
      val cpg = code("pass", "test.py")
      cpg.typ.nameExact("ANY").head.fullName shouldBe "ANY"
    }

    "align METHOD_RETURN evaluationStrategy with x2cpg defaults" in {
      val cpg = code("def f():\n  return 1\n", "test.py")
      val mr  = cpg.method.name("f").methodReturn.head
      mr.evaluationStrategy shouldBe EvaluationStrategies.BY_VALUE
    }
  }
}
