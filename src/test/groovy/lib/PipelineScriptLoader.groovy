package lib

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

// Loads a Jenkins pipeline library script (e.g. pipelines/lib/util.groovy)
// outside Jenkins/JenkinsPipelineUnit, so its pure helper methods can be
// unit-tested on a Java 8 JVM (JPU requires Java 9+).
class PipelineScriptLoader {
  static Object load(String path) {
    return shell().parse(new File(path)).run()
  }

  // Compile without running. Job pipelines (as opposed to lib/ scripts) cannot
  // be run here -- `notify = load '...'` yields null under StepSwallowingScript
  // and the following `notify.wrap` NPEs -- but compiling them still catches
  // syntax and structural errors that would otherwise only surface in Jenkins.
  static Script parse(String path) {
    return shell().parse(new File(path))
  }

  private static GroovyShell shell() {
    def ic = new ImportCustomizer()
    ic.addImports('com.cloudbees.groovy.cps.NonCPS')

    def cc = new CompilerConfiguration()
    cc.addCompilationCustomizers(ic)
    cc.scriptBaseClass = StepSwallowingScript.name

    return new GroovyShell(
      PipelineScriptLoader.classLoader,
      new Binding(),
      cc,
    )
  }
}
