package io.joern.pysrc2cpg

import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.Languages

object Py2Cpg {
  case class InputPair(content: String, relFileName: String)
  type InputProvider = () => InputPair
}

/** Entry point for general cpg generation from python code.
  *
  * @param inputProviders
  *   Set of functions which provide InputPairs. The functions must be safe to call from different threads.
  * @param outputCpg
  *   Empty target cpg which will be populated.
  * @param inputPath
  *   The project root.
  * @param requirementsTxt
  *   The configured name of the requirements txt file.
  * @param schemaValidationMode
  *   The boolean switch for enabling or disabling early schema checking during AST creation.
  */
class Py2Cpg(inputProviders: Iterable[Py2Cpg.InputProvider], outputCpg: Cpg, config: Py2CpgOnFileSystemConfig) {
  def buildCpg(): Unit = {
    new MetaDataPass(outputCpg, Languages.PYTHONSRC, config.inputPath).createAndApply()
    new CodeToCpg(outputCpg, inputProviders, config.schemaValidation, !config.disableFileContent).createAndApply()
    new ConfigFileCreationPass(outputCpg, config.requirementsTxt, config).createAndApply()
    new DependenciesFromRequirementsTxtPass(outputCpg).createAndApply()
    PyTypeNodePass.withTypesFromCpg(outputCpg).createAndApply()
  }
}
