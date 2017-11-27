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

  // we should try hard not to delay and/or drop user submitted jobs.  However,
  // it is porobably safe to assume that most users will have given up or
  // abandoned the build after some period of time.  This value is a random
  // guess and may need to be adjusted.
  timeout(time: 24, unit: 'HOURS') {
    run()
  }
} // notify.wrap

@NonCPS
def addToMatrix(Map matrix, String label, String python) {
  matrix["${label}.${python}"] = {
    util.lsstswBuild(label, python)
  }
}
