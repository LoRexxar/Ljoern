package io.joern.gosrc2cpg

import io.joern.gosrc2cpg.datastructures.GoGlobal
import io.joern.gosrc2cpg.model.GoModHelper
import io.joern.gosrc2cpg.parser.GoAstJsonParser
import io.joern.gosrc2cpg.passes.*
import io.joern.gosrc2cpg.utils.GoAstGenRunner
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.astgen.AstGenRunner.AstGenProgramMetaData
import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.joern.x2cpg.utils.FrontendProfiling
import io.joern.x2cpg.utils.Report
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.shiftleft.semanticcpg.utils.FileUtil

import java.nio.file.Paths
import scala.util.Try

class GoSrc2Cpg(goGlobalOption: Option[GoGlobal] = Option(GoGlobal())) extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig = Config()

  private val report: Report = new Report()

  private var goMod: Option[GoModHelper] = None
  def createCpg(config: Config): Try[Cpg] = {
    FrontendProfiling.run("gosrc2cpg", config.inputPath) {
      withNewEmptyCpg(config.outputPath, config) { (cpg, config) =>
      FileUtil.usingTemporaryDirectory("gosrc2cpgOut") { tmpDir =>
        FrontendProfiling.time("MetaDataPass") { MetaDataPass(cpg, Languages.GOLANG, config.inputPath).createAndApply() }
        val astGenResults = FrontendProfiling.time("GoAstGenRunner") { new GoAstGenRunner(config).executeForGo(tmpDir) }
        astGenResults.foreach { astGenResult =>
          goGlobalOption
            .orElse(Option(GoGlobal()))
            .foreach { goGlobal =>
              goMod = Some(
                GoModHelper(
                  Some(astGenResult.modulePath),
                  astGenResult.parsedModFile
                    .flatMap(modFile => GoAstJsonParser.readModFile(Paths.get(modFile)).map(x => x))
                )
              )
              goGlobal.mainModule = goMod.flatMap(modHelper => modHelper.getModMetaData().map(mod => mod.module.name))
              FrontendProfiling.time("InitialMainSrcPass") {
                InitialMainSrcPass(cpg, astGenResult.parsedFiles, config, goMod.get, goGlobal, tmpDir).createAndApply()
              }
              if (goGlobal.pkgLevelVarAndConstantAstMap.size() > 0) {
                FrontendProfiling.time("PackageCtorCreationPass") {
                  PackageCtorCreationPass(cpg, config, goGlobal).createAndApply()
                }
              }
              if (config.fetchDependencies) {
                goGlobal.processingDependencies = true
                FrontendProfiling.time("DownloadDependenciesPass") {
                  DownloadDependenciesPass(cpg, goMod.get, goGlobal, config).process()
                }
                goGlobal.processingDependencies = false
              }
              FrontendProfiling.time("AstCreationPass") {
                AstCreationPass(cpg, astGenResult.parsedFiles, config, goMod.get, goGlobal, tmpDir, report)
                  .createAndApply()
              }
              report.print()
            }
        }
      }
    }
    }
  }

  def getGoModHelper: GoModHelper = goMod.get
}
