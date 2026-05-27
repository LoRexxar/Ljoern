package io.joern.x2cpg.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CanonicalNameTests extends AnyWordSpec with Matchers {
  "CanonicalName" should {
    "normalize inner class separator $ -> ." in {
      CanonicalName.normalizeTypeFullName("a.b.Outer$Inner") shouldBe "a.b.Outer.Inner"
      CanonicalName.normalizeMethodFullName("a.b.Outer$Inner.m:void()") shouldBe "a.b.Outer.Inner.m:void()"
    }

    "erase generics in typeFullName and signature" in {
      CanonicalName.normalizeTypeFullName("java.util.List<java.lang.String>") shouldBe "java.util.List"
      CanonicalName.normalizeSignature(
        "java.util.List<java.lang.String>(java.util.Map<java.lang.String,java.lang.Integer>)"
      ) shouldBe "java.util.List(java.util.Map)"
    }

    "preserve unresolvedSignature" in {
      CanonicalName.normalizeSignature("<unresolvedSignature>(2)") shouldBe "<unresolvedSignature>(2)"
      CanonicalName.normalizeMethodFullName("X.f:<unresolvedSignature>(2)") shouldBe "X.f:<unresolvedSignature>(2)"
    }
  }
}

