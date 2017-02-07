import util.Common
Common.makeFolders(this)

def job = job('ci-ci/test-freestyle') {

  properties {
    rebuild {
      autoRebuild()
    }
  }

  steps {
    shell('echo hi')
  }
}

Common.addNotification(job)
