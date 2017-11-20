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
  def image = null
  def hub_repo = 'lsstsqre/travissync'

  node('docker') {
    stage('pull') {
      image = docker.image("${hub_repo}:latest")
      image.pull()
    }

    withCredentials([[
      $class: 'StringBinding',
      credentialsId: 'github-api-token-sqrbot',
      variable: 'GITHUB_TOKEN'
    ]]) {
      image.inside {
        stage('run travissync') {
          sh "sqre-travissync"
        }
      }
    }
  }
} // notify.wrap
