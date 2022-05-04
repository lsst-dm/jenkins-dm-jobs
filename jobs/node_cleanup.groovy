import util.Common
Common.makeFolders(this)

job('sqre/infra/jenkins-node-cleanup') {
  parameters {
    stringParam('CLEANUP_THRESHOLD', '200', 'minimum free space remaining on a node, in GiB, to trigger a cleanup')
    booleanParam('FORCE_CLEANUP', false, 'Force cleanup of node workspace(s) regardless of free space remaining threshold. Note that the workspace of active jobs *will not* be cleaned up.')
    stringParam('FORCE_NODE', '', 'Force cleanup of workspace(s) on this node regardless of free space remaining threshold. Note that the workspace of active jobs *will not* be cleaned up.')
  }

  // don't tie up a beefy build worker
  label('jenkins-manager')
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
    )) {
      binding('CLEANUP_THRESHOLD', 'CLEANUP_THRESHOLD')
      binding('FORCE_CLEANUP', 'FORCE_CLEANUP')
      binding('FORCE_NODE', 'FORCE_NODE')
      sandbox(false)
    }
  }
}
