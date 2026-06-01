package io.joern.jssrc2cpg

import io.joern.jssrc2cpg.passes.*
import io.joern.jssrc2cpg.utils.AstGenRunner
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.frontendspecific.jssrc2cpg.postProcessingPasses
import io.joern.x2cpg.passes.callgraph.{MethodRefLinker, NaiveCallLinker}
import io.joern.x2cpg.passes.frontend.XTypeRecoveryConfig
import io.joern.x2cpg.utils.{FrontendProfiling, HashUtil, Report}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.CpgPassBase
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.shiftleft.semanticcpg.utils.FileUtil

import java.nio.file.Paths
import scala.util.Try

class JsSrc2Cpg extends X2CpgFrontend {
  override type ConfigType = Config
  override val defaultConfig: Config = Config()

  def createCpg(config: Config): Try[Cpg] = {
    FrontendProfiling.run("jssrc2cpg", config.inputPath) {
    withNewEmptyCpg(config.outputPath, config) { (cpg, config) =>
      FileUtil.usingTemporaryDirectory("jssrc2cpgOut") { tmpDir =>
        val report       = new Report()
        val astGenResult = FrontendProfiling.time("AstGenRunner") {
          new AstGenRunner(config).execute(tmpDir)
        }
        val hash = HashUtil.sha256(astGenResult.parsedFiles.map(Paths.get(_)))

        val astCreationPass = FrontendProfiling.time("AstCreationPass") {
          val pass = new AstCreationPass(cpg, astGenResult, config, report)(config.schemaValidation)
          pass.createAndApply()
          pass
        }

        FrontendProfiling.time("JavaScriptTypeNodePass") {
          JavaScriptTypeNodePass.withRegisteredTypes(astCreationPass.typesSeen(), cpg).createAndApply()
        }
        FrontendProfiling.time("JavaScriptMetaDataPass") {
          new JavaScriptMetaDataPass(cpg, hash, config.inputPath).createAndApply()
        }
        FrontendProfiling.time("DependenciesPass") {
          new DependenciesPass(cpg, config).createAndApply()
        }
        FrontendProfiling.time("ConfigPass") {
          new ConfigPass(cpg, config, report).createAndApply()
        }
        FrontendProfiling.time("PrivateKeyFilePass") {
          new PrivateKeyFilePass(cpg, config, report).createAndApply()
        }
        FrontendProfiling.time("ImportsPass") {
          new ImportsPass(cpg).createAndApply()
        }

        report.print()
      }
    }
    }
  }

}
