package io.joern.pysrc2cpg.cpg

import io.joern.pysrc2cpg.ModuleFullName
import io.joern.pysrc2cpg.testfixtures.PySrc2CpgFixture
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

import java.io.File

class FullNameRewritePhaseBTests extends PySrc2CpgFixture(withPostProcessing = false) with Matchers {
  "Phase B fullName rewrite" should {
    "derive moduleFullName from relFileName" in {
      ModuleFullName.fromRelFileName("mod.py") shouldBe "mod"
      ModuleFullName.fromRelFileName(Seq("pkg", "sub", "mod.py").mkString(File.separator)) shouldBe "pkg.sub.mod"
      ModuleFullName.fromRelFileName(Seq("pkg", "__init__.py").mkString(File.separator)) shouldBe "pkg"
    }

    "rewrite METHOD.fullName to module-based naming" in {
      val path = Seq("a", "b.py").mkString(File.separator)
      val cpg  = code("def method():\n  pass\n", path)
      cpg.method.nameExact("method").head.fullName shouldBe "a.b.method"
    }

    "rewrite TYPE_DECL.fullName to module-based naming" in {
      val path = Seq("a", "b.py").mkString(File.separator)
      val cpg  = code("class Foo:\n  pass\n", path)
      cpg.typeDecl.nameExact("Foo").head.fullName shouldBe "a.b.Foo"
      cpg.typeDecl.nameExact("Foo<meta>").head.fullName shouldBe "a.b.Foo<meta>"
    }

    "resolve CALL.methodFullName for same-module calls" in {
      val path = Seq("a", "b.py").mkString(File.separator)
      val cpg  = code("def foo():\n  pass\n\nfoo()\n", path)
      cpg.call.nameExact("foo").head.methodFullName shouldBe "a.b.foo"
    }

    "fallback CALL.methodFullName to module-based guess" in {
      val path = Seq("a", "b.py").mkString(File.separator)
      val cpg  = code("unknown()\n", path)
      cpg.call.nameExact("unknown").head.methodFullName shouldBe "a.b.unknown"
    }

    "resolve CALL.methodFullName via imports for simple and attribute calls" in {
      val path = Seq("a", "b.py").mkString(File.separator)
      val cpg = code(
        "from c.d import foo as bar\n" +
          "bar()\n" +
          "import e.f as m\n" +
          "m.baz()\n",
        path
      )

      cpg.call.nameExact("bar").head.methodFullName shouldBe "c.d.foo"
      cpg.call.nameExact("baz").head.methodFullName shouldBe "e.f.baz"
    }
  }
}
