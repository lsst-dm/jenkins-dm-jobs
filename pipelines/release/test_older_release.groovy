node('jenkins-manager') {
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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireEnvVars([
    'PRODUCTS',
    'VERSIONS',
  ])


  def run = {
    stage('build older versions') {
      def rubinVers = VERSIONS.split(',').collect { it.trim() }
      util.buildOlderVersionMatrix(rubinVers, PRODUCTS)
    }
  }

  timeout(time: 1, unit: 'HOURS') {
    run()
  }
} // notify.wrap
