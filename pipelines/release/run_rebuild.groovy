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
  def versiondbPush = 'true'
  if (params.NO_VERSIONDB_PUSH) {
    versiondbPush = 'false'
  }

  def timelimit = params.TIMEOUT.toInteger()
  def awsImage  = 'docker.io/lsstsqre/awscli'

  def run = {
    ws('snowflake/release') {
      stage('build') {
        withEnv([
          "EUPS_PKGROOT=${pwd()}/distrib",
          'VERSIONDB_REPO=git@github.com:lsst/versiondb.git',
          "VERSIONDB_PUSH=${versiondbPush}",
          'GIT_SSH_COMMAND=ssh -o StrictHostKeyChecking=no',
          "LSST_JUNIT_PREFIX=centos-7.py3",
          'python=py3',
         ]) {
          sshagent (credentials: ['github-jenkins-versiondb']) {
            util.jenkinsWrapper()
          } // sshagent
        } // withEnv
      } // stage('build')

      stage('push docs') {
        withCredentials([[
          $class: 'UsernamePasswordMultiBinding',
          credentialsId: 'aws-doxygen-push',
          usernameVariable: 'AWS_ACCESS_KEY_ID',
          passwordVariable: 'AWS_SECRET_ACCESS_KEY'
        ],
        [
          $class: 'StringBinding',
          credentialsId: 'doxygen-push-bucket',
          variable: 'DOXYGEN_S3_BUCKET'
        ]]) {
          withEnv([
            "EUPS_PKGROOT=${pwd()}/distrib",
            "WORKSPACE=${pwd()}",
            "HOME=${pwd()}/home",
          ]) {
            util.insideWrap(awsImage) {
              util.shColor '''
                # provides DOC_PUSH_PATH
                . ./buildbot-scripts/settings.cfg.sh

                aws s3 cp --recursive "$DOC_PUSH_PATH"/ s3://$DOXYGEN_S3_BUCKET/stack/doxygen/
              '''
            } // util.insideWrap
          }
        }
      } // stage('push docs')
    } // ws
  } // run

  node('jenkins-snowflake-1') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
