def notify = null

node('jenkins-master') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
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
    addToMatrix(matrix, 'osx', 'py2')

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
  matrix["${label},${python}"] = {
    util.lsstswBuild(label, python)
  }
}
