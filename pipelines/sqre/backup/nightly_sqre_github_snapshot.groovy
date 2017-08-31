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
  def hub_repo = 'lsstsqre/sqre-github-snapshot'

  node('docker') {
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
