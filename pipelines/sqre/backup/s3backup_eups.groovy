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

  def hub_repo = 'lsstsqre/s3backup'

  node('docker') {
    def image = docker.image("${hub_repo}:latest")

    stage('pull') {
      image.pull()
    }

    stage('backup') {
      withCredentials([
        [
          $class: 'UsernamePasswordMultiBinding',
          credentialsId: 'aws-eups-backup',
          usernameVariable: 'AWS_ACCESS_KEY_ID',
          passwordVariable: 'AWS_SECRET_ACCESS_KEY'
        ],
        [
          $class: 'StringBinding',
          credentialsId: 'eups-push-bucket',
          variable: 'S3_SRC_BUCKET'
        ],
        [
          $class: 'StringBinding',
          credentialsId: 'eups-backup-bucket',
          variable: 'S3_BACKUP_BUCKET'
        ]
      ]) {
        withEnv(["IMAGE=${image.id}"]) {
          sh '''
          docker run \
            -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
            -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
            -e S3_SRC_BUCKET="$S3_SRC_BUCKET" \
            -e S3_BACKUP_BUCKET="$S3_BACKUP_BUCKET" \
            "$IMAGE"
          '''.replaceFirst("\n","").stripIndent()
        }
      }
    } // stage('backup')
  } // node('docker')
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
