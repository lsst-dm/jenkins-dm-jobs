import util.Common
Common.makeFolders(this)

job('sqre/infra/jenkins-node-cleanup') {
  // don't tie up a beefy build slave
  label('jenkins-master')
  concurrentBuild(false)

  triggers {
    cron('H/10 * * * *')
  }

  wrappers {
    timeout {
      // minutes
      absolute(59)
    }
  }

  // The groovy script needs to be inlined in the job in order to avoid being
  // executed under the security sandbox.
  // There appears to be no way to disable the sandbox for the
  // #systemGroovyScriptFile step
  steps {
    systemGroovyCommand(readFileFromWorkspace(
      'scripts/sqre/infra/jenkins_node_cleanup.groovy'
    ))
  }
}
