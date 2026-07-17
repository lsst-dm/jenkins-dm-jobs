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
  def infraJob = 'sqre/infra/infra-monthly'

  stage('run infra-monthly') {
    build job: infraJob
  }
} // notify.wrap
