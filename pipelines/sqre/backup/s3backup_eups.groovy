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
  def hub_repo = 'lsstsqre/s3backup'

  def run = {
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
  } // pull

  node('docker') {
    timeout(time: 4, unit: 'HOURS') {
      run()
    }
  } // node('docker')
} // notify.wrap
