package io.joern.javasrc2cpg.util

import io.joern.javasrc2cpg.testfixtures.SourceCodeFixture

class SourceParserCollectPackagePrefixAllowlistTests extends SourceCodeFixture {
  "SourceParser.collectPackagePrefixAllowlist" should {
    "collect prefixes from package and import statements" in {
      val testDir = emptyWriter
        .moreCode(
          """package a.b.c;
            |import foo.bar.Baz;
            |import foo.bar.qux.*;
            |import static com.example.Util.method;
            |import static com.example.Util.*;
            |import java.util.List;
            |import java.io.*;
            |/* import should.not.Match; */
            |// import also.should.not.Match;
            |class A {}
            |""".stripMargin,
          "src/main/java/a/b/c/A.java"
        )
        .moreCode(
          """package single;
            |import javax.net.ssl.SSLContext;
            |class B {}
            |""".stripMargin,
          "B.java"
        )
        .writeCode(".java")

      val allowlist = SourceParser.collectPackagePrefixAllowlist(
        testDir,
        List("src/main/java/a/b/c/A.java", "B.java")
      )

      allowlist shouldBe Set("a.b", "foo.bar", "com.example", "java.util", "java.io", "single", "javax.net")
    }
  }
}

