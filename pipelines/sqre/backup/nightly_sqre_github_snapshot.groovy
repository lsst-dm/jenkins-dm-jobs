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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  def hub_repo = 'lsstsqre/sqre-github-snapshot'

  def image = null

  def run = {
    stage('pull') {
      image = docker.image("${hub_repo}:latest")
      image.pull()
    }

    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'github_backup',
      usernameVariable: 'AWS_ACCESS_KEY_ID',
      passwordVariable: 'AWS_SECRET_ACCESS_KEY'
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'github-api-token-sqreadmin',
      variable: 'GITHUB_TOKEN'
    ]]) {
      image.inside {
        stage('run github-snapshot') {
          sh "github-snapshot"
        }
        stage('run snapshot-purger') {
          sh "snapshot-purger"
        }
      }
    }
  } // run

  util.nodeWrap('docker') {
    timeout(time: 3, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
