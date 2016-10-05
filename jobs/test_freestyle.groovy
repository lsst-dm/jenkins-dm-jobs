import util.Common

def job = job('ci-ci/test-freestyle') {

  properties {
    rebuild {
      autoRebuild()
    }
  }

  steps {
    shell('echo hi')
  }

  publishers {
    // must be defined even to use the global defaults
    hipChat {}
  }
}

Common.addNotification(job)
