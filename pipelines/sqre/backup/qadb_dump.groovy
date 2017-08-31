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

  def hub_repo = 'lsstsqre/mysqldump_to_s3'

  node('docker') {
    def image = docker.image("${hub_repo}:latest")

    stage('pull') {
      image.pull()
    }

    dbdump(image, 'all')
    dbdump(image, 'qadb', 'qadb')
    // convience "dumps" to make it easy to find the most recent dump
    dbdump(image, 'all', null, 'latest')
    dbdump(image, 'qadb', 'qadb', 'latest')
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

def dbdump(image, String prefix, String db=null, String dump_name=null) {

  withCredentials([
    [
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'aws-qadb-backup',
      usernameVariable: 'AWS_ACCESS_KEY_ID',
      passwordVariable: 'AWS_SECRET_ACCESS_KEY'
    ],
    [
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'qadb-admin',
      usernameVariable: 'MYSQL_ENV_MYSQL_USER',
      passwordVariable: 'MYSQL_ENV_MYSQL_PASSWORD'
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'qadb-fqdn',
      variable: 'MYSQL_PORT_3306_TCP_ADDR'
    ],
    [
      $class: 'StringBinding',
      credentialsId: 'qadb-backup-s3-bucket',
      variable: 'AWS_BUCKET'
    ]
  ]) {
    def env = [
      "IMAGE=${image.id}",
      "CREATE_AWS_BUCKET=false",
      "PREFIX=${prefix}",
      'MYSQL_PORT_3306_TCP_PORT=3306'
    ]

    // default is '--all-databases'
    if (db) {
      env << "MYSQLDUMP_DATABASE=--databases ${db}"
    }

    if (dump_name) {
      env << "DUMP_NAME=${dump_name}"
    }

    withEnv(env) {
      def stageName = "dump"
      if (db) {
        stageName += " (${db})"
      } else {
        stageName += " (all}"
      }
      if (dump_name) {
        stageName += " - ${dump_name}"
      }
      stage(stageName) {
        sh '''
        docker run \
          -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
          -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
          -e AWS_BUCKET="$AWS_BUCKET" \
          -e PREFIX="$PREFIX" \
          -e MYSQL_ENV_MYSQL_USER="$MYSQL_ENV_MYSQL_USER" \
          -e MYSQL_ENV_MYSQL_PASSWORD="$MYSQL_ENV_MYSQL_PASSWORD" \
          -e MYSQL_PORT_3306_TCP_ADDR="$MYSQL_PORT_3306_TCP_ADDR" \
          -e MYSQL_PORT_3306_TCP_PORT="$MYSQL_PORT_3306_TCP_PORT" \
          -e CREATE_AWS_BUCKET="$CREATE_AWS_BUCKET" \
          -e MYSQLDUMP_DATABASE="$MYSQLDUMP_DATABASE" \
          -e DUMP_NAME="$DUMP_NAME" \
          "$IMAGE"
        '''.replaceFirst("\n","").stripIndent()
      }
    }
  }
}
