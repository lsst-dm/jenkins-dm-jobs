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
  util.requireParams(['TYPE'])
  String type = params.TYPE

  switch(type) {
    case 'DAILY':
    case 'WEEKLY':
    case 'MONTHLY':
      break
    default:
      error "TYPE parameter value must be one of 'DAILY','WEEKLY','MONTHLY'"
  }

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
        withEnv([
          "IMAGE=${image.id}",
          "S3_BACKUP_PREFIX=${type.toLowerCase()}",
        ]) {
          sh '''
          docker run \
            -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
            -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
            -e S3_SRC_BUCKET="$S3_SRC_BUCKET" \
            -e S3_BACKUP_BUCKET="$S3_BACKUP_BUCKET" \
            -e S3_BACKUP_PREFIX="$S3_BACKUP_PREFIX" \
            "$IMAGE"
          '''.replaceFirst("\n","").stripIndent()
        }
      }
    } // stage('backup')
  } // pull

  util.nodeWrap('linux-64') {
    timeout(time: 4, unit: 'HOURS') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
