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

notify.wrap {
  stage('build') {
    def matrix = [:]

    addToMatrix(matrix, 'centos-6', 'py3')
    addToMatrix(matrix, 'centos-7', 'py2')
    addToMatrix(matrix, 'centos-7', 'py3')
    addToMatrix(matrix, 'osx', 'py3')

    parallel matrix
  } // stage
} // notify.wrap

@NonCPS
def addToMatrix(Map matrix, String label, String python) {
  matrix["${label}.${python}"] = {
    util.lsstswBuild(label, python, true)
  }
}
