def notify = null

node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    // XXX the git step seemed to blowup on a branch of '*/<foo>'
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
  }
}

try {
  notify.started()

  stage('build') {
    def matrix = [:]

    addToMatrix(matrix, 'centos-6', 'py2')
    addToMatrix(matrix, 'centos-7', 'py2')
    addToMatrix(matrix, 'centos-7', 'py3')
    addToMatrix(matrix, 'osx', 'py3')

    parallel matrix
  } // stage
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}

@NonCPS
def addToMatrix(Map matrix, String label, String python) {
  matrix["${label}.${python}"] = {
    util.lsstswBuild(label, python)
  }
}
