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
  def testOlderRelease = 'release/test-older-release'

  stage('run test-older-release') {
    build job: testOlderRelease,
      parameters: [
        stringParam(name: 'VERSIONS', value:"o_latest, v29_2_1, v24_1_7"),
        stringParam(name: 'PRODUCTS', value:scipipe.canonical.products )
      ]
  }
} // notify.wrap
