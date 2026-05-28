package io.joern.jimple2cpg

import io.joern.jimple2cpg.passes.{AstCreationPass, DeclarationRefPass, SootAstCreationPass}
import io.joern.jimple2cpg.util.Decompiler
import io.joern.jimple2cpg.util.ProgramHandlingUtil.{ClassFile, extractClassesInPackageLayout, listClassesInArchive}
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass, MetaDataPass, TypeNodePass}
import io.joern.x2cpg.utils.FrontendProfiling
import io.shiftleft.semanticcpg.utils.FileUtil.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.utils.FileUtil
import org.slf4j.LoggerFactory
import soot.options.Options
import soot.{G, Scene}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.language.postfixOps
import scala.util.Try

object Jimple2Cpg {
  val language = "JAVA"

  def apply(): Jimple2Cpg = new Jimple2Cpg()
}

class Jimple2Cpg extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig = Config()

  import Jimple2Cpg.*

  private val logger = LoggerFactory.getLogger(classOf[Jimple2Cpg])

  private def sootLoadApk(input: Path, framework: Option[String] = None): Unit = {
    Options.v().set_process_dir(List(input.absolutePathAsString).asJava)
    framework match {
      case Some(value) if value.nonEmpty =>
        Options.v().set_src_prec(Options.src_prec_apk)
        Options.v().set_force_android_jar(value)
      case _ =>
        Options.v().set_src_prec(Options.src_prec_apk_c_j)
    }
    Options.v().set_process_multiple_dex(true)
    // workaround for Soot's bug while parsing large apk.
    // see: https://github.com/soot-oss/soot/issues/1256
    Options.v().setPhaseOption("jb", "use-original-names:false")
  }

  /** Load all class files from archives or directories recursively
    * @param recurse
    *   Whether to unpack recursively
    * @param depth
    *   Maximum depth of recursion
    * @return
    *   The list of extracted class files whose package path could be extracted, placed on that package path relative to
    *   [[tmpDir]]
    */
  private def loadClassFiles(src: Path, tmpDir: Path, recurse: Boolean, depth: Int): List[ClassFile] = {
    extractClassesInPackageLayout(
      src,
      tmpDir,
      isClass = e => e.extension.contains(".class"),
      isArchive = e => e.isZipFile,
      isConfigFile = e => e.isConfigFile,
      recurse,
      depth
    )
  }

  /** Extract all class files found, place them in their package layout and load them into soot.
    * @param input
    *   The file/directory to traverse for class files.
    * @param tmpDir
    *   The directory to place the class files in their package layout
    */
  private def sootLoad(input: Path, tmpDir: Path, config: Config): List[ClassFile] = {
    val classFiles =
      if (!config.recurse && Files.isRegularFile(input) && input.extension().exists(x => x == ".jar" || x == ".zip")) {
        Options.v().set_soot_classpath(input.absolutePathAsString)
        Options.v().set_prepend_classpath(true)
        listClassesInArchive(input, tmpDir, isClass = e => e.extension.contains(".class"), isConfigFile = _ => false)
      } else {
        Options.v().set_soot_classpath(tmpDir.absolutePathAsString)
        Options.v().set_prepend_classpath(true)
        loadClassFiles(input, tmpDir, config.recurse, config.depth)
      }
    val fullyQualifiedClassNames = classFiles.flatMap(_.fullyQualifiedClassName)
    logger.info(s"Loading ${classFiles.size} program files")
    logger.debug(s"Source files are: ${classFiles.map(_.file.absolutePathAsString)}")
    fullyQualifiedClassNames.foreach { fqcn =>
      Scene.v().addBasicClass(fqcn)
      Scene.v().loadClassAndSupport(fqcn)
    }
    classFiles
  }

  /** Apply the soot passes
    * @param tmpDir
    *   A temporary directory that will be used as the classpath for extracted class files
    */
  private def cpgApplyPasses(cpg: Cpg, config: Config, tmpDir: Path): Unit = {
    val input = Paths.get(config.inputPath)
    val archiveFastPath = !config.recurse && Files.isRegularFile(input) && input.extension().exists(x => x == ".jar" || x == ".zip")
    FrontendProfiling.time("configureSoot") { configureSoot(config, tmpDir) }
    FrontendProfiling.time("MetaDataPass") { new MetaDataPass(cpg, language, config.inputPath).createAndApply() }
    val usedTypesFromAstCreation: () => Set[String] = input.extension() match {
      case Some(".apk" | ".dex") if Files.isRegularFile(input) =>
        FrontendProfiling.time("sootLoadApk") { sootLoadApk(input, config.android) }
        { () =>
          val astCreator = FrontendProfiling.time("SootAstCreationPass.init") { SootAstCreationPass(cpg, config) }
          FrontendProfiling.time("SootAstCreationPass.run") { astCreator.createAndApply() }
          astCreator.usedTypes()
        }
      case _ =>
        val classFiles = FrontendProfiling.time("sootLoad") { sootLoad(input, tmpDir, config) }
        FrontendProfiling.metric("classFiles", classFiles.size.toLong)
        val decompiledSources =
          FrontendProfiling.time("decompileClassFiles") {
            decompileClassFiles(classFiles, !config.disableFileContent, Option.when(archiveFastPath)(input))
          }

        { () =>
          val astCreator =
            FrontendProfiling.time("AstCreationPass.init") { AstCreationPass(classFiles, cpg, config, decompiledSources) }
          FrontendProfiling.time("AstCreationPass.run") { astCreator.createAndApply() }
          astCreator.usedTypes()
        }
    }

    FrontendProfiling.time("Scene.loadNecessaryClasses") {
      logger.info("Loading classes to soot")
      Scene.v().loadNecessaryClasses()
      logger.info(s"Loaded ${Scene.v().getApplicationClasses.size()} classes")
    }

    val usedTypes = usedTypesFromAstCreation()
    FrontendProfiling.time("TypeNodePass") {
      TypeNodePass
        .withRegisteredTypes(usedTypes, cpg)
        .createAndApply()
    }
    FrontendProfiling.time("DeclarationRefPass") { DeclarationRefPass(cpg).createAndApply() }
    val configFilesRoot = Option.when(!archiveFastPath)(tmpDir.toString)
    FrontendProfiling.time("JavaConfigFileCreationPass") { JavaConfigFileCreationPass(cpg, configFilesRoot, config).createAndApply() }
  }

  private def decompileClassFiles(
    classFiles: List[ClassFile],
    decompileJava: Boolean,
    archiveInput: Option[Path]
  ): Map[String, String] = {
    if (!decompileJava) {
      Map.empty
    } else {
      val inputs = archiveInput.map(List(_)).getOrElse(classFiles.map(_.file))
      val decompiler = new Decompiler(inputs)
      decompiler.decompile().toMap
    }
  }

  override def createCpg(config: Config): Try[Cpg] = {
    FrontendProfiling.run("jimple2cpg", config.inputPath) {
      try {
        withNewEmptyCpg(config.outputPath, config: Config) { (cpg, config) =>
          val tmpDir = Files.createTempDirectory("jimple2cpg-")
          try {
            cpgApplyPasses(cpg, config, tmpDir)
          } finally {
            FrontendProfiling.time("cleanup.tmpDir") { FileUtil.delete(tmpDir) }
          }
        }
      } finally {
        G.reset()
      }
    }
  }

  private def configureSoot(config: Config, outDir: Path): Unit = {
    // set application mode
    Options.v().set_app(false)
    Options.v().set_whole_program(false)
    // keep debugging info
    Options.v().set_keep_line_number(true)
    Options.v().set_keep_offset(true)
    // ignore library code
    Options.v().set_no_bodies_for_excluded(true)
    Options.v().set_allow_phantom_refs(true)
    // keep variable names
    Options.v().setPhaseOption("jb.sils", "enabled:false")
    Options.v().setPhaseOption("jb", "use-original-names:true")
    // Keep exceptions
    Options.v().set_show_exception_dests(true)
    Options.v().set_omit_excepting_unit_edges(false)
    Options.v().set_output_format(Options.output_format_none)
    Options.v().set_output_dir(outDir.absolutePathAsString)

    Options.v().set_dynamic_dir(config.dynamicDirs.asJava)
    Options.v().set_dynamic_package(config.dynamicPkgs.asJava)

    if (config.fullResolver) {
      // full transitive resolution of all references
      Options.v().set_full_resolver(true)
    }
  }
}
