package io.joern.jimple2cpg.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProgramHandlingUtilTests extends AnyWordSpec with Matchers {

  "normalizeClassEntryPath" should {
    "normalize class entry paths from ZipEntry names" in {
      ProgramHandlingUtil.normalizeClassEntryPath("com/foo/Bar.class") shouldBe Some("com/foo/Bar")
      ProgramHandlingUtil.normalizeClassEntryPath("com\\foo\\Bar.class") shouldBe Some("com/foo/Bar")
    }

    "strip BOOT-INF/classes prefix" in {
      ProgramHandlingUtil.normalizeClassEntryPath("BOOT-INF/classes/com/foo/Bar.class") shouldBe Some("com/foo/Bar")
    }

    "strip WEB-INF/classes prefix" in {
      ProgramHandlingUtil.normalizeClassEntryPath("WEB-INF/classes/com/foo/Bar.class") shouldBe Some("com/foo/Bar")
    }

    "strip META-INF/versions/<n> prefix" in {
      ProgramHandlingUtil.normalizeClassEntryPath("META-INF/versions/9/com/foo/Bar.class") shouldBe Some("com/foo/Bar")
    }

    "normalize extracted archive file paths" in {
      ProgramHandlingUtil.normalizeClassEntryPath("C:\\tmp\\extract-archive-123\\com\\foo\\Bar.class") shouldBe Some(
        "com/foo/Bar"
      )
    }

    "return None for non-class entries and non-extracted absolute paths" in {
      ProgramHandlingUtil.normalizeClassEntryPath("com/foo/Bar.java") shouldBe None
      ProgramHandlingUtil.normalizeClassEntryPath("C:\\classes\\com\\foo\\Bar.class") shouldBe None
    }
  }
}

