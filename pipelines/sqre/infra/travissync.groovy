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
  // the timeout should be <= the cron triggering interval to prevent builds
  // pilling up in the backlog.
  timeout(time: 59, unit: 'MINUTES') {
    doTravissync()
  }
} // notify.wrap

def void doTravissync() {
  def hub_repo = 'lsstsqre/travissync'

  def image = null

  node('docker') {
    stage('pull') {
      image = docker.image("${hub_repo}:latest")
      image.pull()
    }

    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'github-api-token-sqreadmin',
      variable: 'GITHUB_TOKEN'
    ]]) {
      image.inside {
        stage('run travissync') {
          sh "sqre-travissync"
        }
      }
    }
  }
} // doTravissync
