package io.joern.jimple2cpg.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JavaMethodSourceExtractorTests extends AnyWordSpec with Matchers {

  "JavaMethodSourceExtractor.extract" should {
    "extract a method by name and parameter types" in {
      val src =
        """package p;
          |
          |class Foo {
          |  public int add(int x, int y) {
          |    return x + y;
          |  }
          |
          |  public String add(String x, String y) {
          |    return x + y;
          |  }
          |}
          |""".stripMargin

      val out = JavaMethodSourceExtractor
        .extract(src, "add", List("int", "int"), returnType = Some("int"), isConstructor = false)
        .map(_.code)
        .getOrElse("")

      out should include("public int add(int x, int y)")
      out should include("return x + y;")
      out should not include ("public String add(String x, String y)")
    }

    "extract a method when param types are fully qualified" in {
      val src =
        """class Foo {
          |  public String join(String a, String b) {
          |    return a + b;
          |  }
          |}
          |""".stripMargin

      val out = JavaMethodSourceExtractor
        .extract(src, "join", List("java.lang.String", "java.lang.String"), returnType = Some("java.lang.String"), isConstructor = false)
        .map(_.code)
        .getOrElse("")

      out should include("public String join(String a, String b)")
      out should include("return a + b;")
    }

    "extract a constructor" in {
      val src =
        """class Foo {
          |  public Foo(int x) {
          |    System.out.println(x);
          |  }
          |}
          |""".stripMargin

      val out = JavaMethodSourceExtractor
        .extract(src, "Foo", List("int"), returnType = None, isConstructor = true)
        .map(_.code)
        .getOrElse("")

      out should include("public Foo(int x)")
      out should include("System.out.println(x);")
    }
  }
}

