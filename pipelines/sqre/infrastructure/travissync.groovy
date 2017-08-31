def notify = null

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
  }
}

try {
  notify.started()

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
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}
