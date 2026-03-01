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
  def testOlderRelease = 'release/test-older-release'

  stage('run test-older-release') {
    build job: testOlderRelease,
      parameters: [
        stringParam(name: 'VERSIONS', value:"o_latest, 29.2.1"),
        stringParam(name: 'PRODUCTS', value:scipipe.canonical.products )
      ]
  }
} // notify.wrap
