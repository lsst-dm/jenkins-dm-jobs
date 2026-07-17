node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    def notify = load 'pipelines/lib/notify.groovy'
    def util = load 'pipelines/lib/util.groovy'
    def scipipe = util.scipipeConfig() // needed for side effects
    def sqre = util.sqreConfig() // needed for side effects
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

  timeout(time: 6, unit: 'HOURS') {
    run()
  }
} // notify.wrap
