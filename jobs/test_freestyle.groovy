import util.Common
Common.makeFolders(this)

def folder = 'sqre/ci-ci'

def job = job("${folder}/test-freestyle") {

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
