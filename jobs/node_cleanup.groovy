import util.Common
Common.makeFolders(this)

job('sqre/infrastructure/jenkins-node-cleanup') {
  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)

  triggers {
    cron('H * * * *')
  }

  // The groovy script needs to be inlined in the job in order to avoid being
  // executed under the security sandbox.
  // There appears to be no way to disable the sandbox for the
  // #systemGroovyScriptFile step
  steps {
    systemGroovyCommand(readFileFromWorkspace(
      'scripts/sqre/infrastructure/jenkins_node_cleanup.groovy'
    ))
  }
}
