package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.codepropertygraph.generated.Cpg

class TmpEliminationTests extends PySrc2CpgFixture(withOssDataflow = false) {

  private def assertNoSyntheticLocals(cpg: Cpg): Unit = {
    cpg.local.name("tmp\\d+").l shouldBe Nil
    cpg.local.name("(manager|enter|exit|value)\\d+").l shouldBe Nil
  }

  "tmp elimination" should {

    "not introduce tmp locals for chained calls" in {
      val cpg = code("""
          |def get():
          |  return client
          |
          |class C:
          |  def upload(self, x):
          |    pass
          |
          |client = C()
          |
          |get().upload(1)
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce tmp locals for attribute call chains" in {
      val cpg = code("""
          |def get():
          |  return api
          |
          |class Api:
          |  def client(self):
          |    return self
          |  def upload(self, x):
          |    pass
          |
          |api = Api()
          |get().client().upload(1)
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce tmp locals for destructuring assignment" in {
      val cpg = code("""
          |def foo():
          |  return (1, 2)
          |
          |a, b = foo()
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce tmp locals for starred destructuring assignment" in {
      val cpg = code("""
          |def foo():
          |  return (1, 2, 3, 4)
          |
          |a, *b, c = foo()
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce tmp locals for list comprehensions" in {
      val cpg = code("""
          |xs = [1,2,3]
          |ys = [x + 1 for x in xs if x > 1]
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce tmp locals for dict comprehensions" in {
      val cpg = code("""
          |xs = [(1,2),(3,4)]
          |m = {k: v for k,v in xs}
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce tmp locals for generator comprehensions" in {
      val cpg = code("""
          |xs = [1,2,3]
          |g = (x + 1 for x in xs)
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for compare chains" in {
      val cpg = code("""x = 1; y = 2; z = 3; a = 4; b = x < y < z < a""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for dict literals with unpack" in {
      val cpg = code("""z = {"k": 1}; x = {"a": 1, **z}""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for for lowering" in {
      val cpg = code("""
          |xs=[1,2,3]
          |for x in xs:
          |  pass
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for with lowering" in {
      val cpg = code("""
          |class M:
          |  def __enter__(self): return self
          |  def __exit__(self, t, v, tb): return False
          |m = M()
          |with m as x:
          |  pass
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }

    "not introduce synthetic locals for inheritance call bases" in {
      val cpg = code("""
          |def Foo(): return object
          |class X(Foo()):
          |  pass
          |""".stripMargin).cpg
      assertNoSyntheticLocals(cpg)
    }
  }
}
