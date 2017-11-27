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
  def run = {
    stage('build') {
      def matrix = [:]

      addToMatrix(matrix, 'centos-6', 'py3')
      addToMatrix(matrix, 'centos-7', 'py2')
      addToMatrix(matrix, 'centos-7', 'py3')
      addToMatrix(matrix, 'osx', 'py3')

      parallel matrix
    } // stage
  } // run

  // the timeout should be <= the cron triggering interval to prevent builds
  // pilling up in the backlog.
  timeout(time: 23, unit: 'HOURS') {
    run()
  }
} // notify.wrap

@NonCPS
def addToMatrix(Map matrix, String label, String python) {
  matrix["${label}.${python}"] = {
    util.lsstswBuild(label, python)
  }
}
