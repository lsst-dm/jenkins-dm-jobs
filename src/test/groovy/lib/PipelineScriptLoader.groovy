package lib

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

// Loads a Jenkins pipeline library script (e.g. pipelines/lib/util.groovy)
// outside Jenkins/JenkinsPipelineUnit, so its pure helper methods can be
// unit-tested on a Java 8 JVM (JPU requires Java 9+).
class PipelineScriptLoader {
  static Object load(String path) {
    def ic = new ImportCustomizer()
    ic.addImports('com.cloudbees.groovy.cps.NonCPS')

    def cc = new CompilerConfiguration()
    cc.addCompilationCustomizers(ic)
    cc.scriptBaseClass = StepSwallowingScript.name

    def shell = new GroovyShell(
      PipelineScriptLoader.classLoader,
      new Binding(),
      cc,
    )
    def script = shell.parse(new File(path))
    return script.run()
  }
}
