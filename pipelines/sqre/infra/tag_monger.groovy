node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  def run = {
    stage('retire daily tags') {
      withCredentials([file(
          credentialsId: 'gs-eups-push',
          variable: 'GOOGLE_APPLICATION_CREDENTIALS'
        )])  {
        withEnv([
          "TAG_MONGER_BUCKET=eups-prod",
          "TAG_MONGER_MAX=0",
          "TAG_MONGER_VERBOSE=true",
        ]) {
          container('tag-monger') {
            sh 'tag-monger'
          }
        }
      } // withCredentials
    } // stage
  } // run

  util.nodeWrap('linux-64') {
    timeout(time: 4, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap('linux-64')
} // notify.wrap
