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
  def hub_repo = 'lsstsqre/tag-monger'

  def run = {
    def image = docker.image("${hub_repo}:latest")

    stage('pull') {
      image.pull()
    }

    stage('retire daily tags') {
      withCredentials([
        [
          $class: 'UsernamePasswordMultiBinding',
          credentialsId: 'aws-eups-tag-admin',
          usernameVariable: 'AWS_ACCESS_KEY_ID',
          passwordVariable: 'AWS_SECRET_ACCESS_KEY'
        ],
        [
          $class: 'StringBinding',
          credentialsId: 'eups-push-bucket',
          variable: 'S3_SRC_BUCKET'
        ],
      ]) {
        withEnv([
          "IMAGE=${image.id}",
          "AWS_REGION=us-east-1",
          "TAG_MONGER_BUCKET=${S3_SRC_BUCKET}",
          "TAG_MONGER_MAX=0",
          "TAG_MONGER_PAGESIZE=500",
          "TAG_MONGER_VERBOSE=true",
        ]) {
          image.inside {
            sh 'tag-monger'
          }
        }
      } // withCredentials
    } // stage
  } // run

  node('docker') {
    timeout(time: 4, unit: 'HOURS') {
      run()
    }
  } // node('docker')
} // notify.wrap
