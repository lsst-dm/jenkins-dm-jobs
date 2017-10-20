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
    util = load 'pipelines/lib/util.groovy'
  }
}

try {
  notify.started()

  def versiondbPush = 'true'
  if (params.NO_VERSIONDB_PUSH) {
    versiondbPush = 'false'
  }

  node('jenkins-snowflake-1') {
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
            util.shColor '''
              #!/bin/bash -e

              if [[ $SKIP_DOCS == "true" ]]; then
                exit 0
              fi

              # setup python env
              . "${WORKSPACE}/lsstsw/bin/setup.sh"
              pip install --upgrade awscli==1.11.167

              # provides DOC_PUSH_PATH
              . ./buildbot-scripts/settings.cfg.sh

              aws s3 cp --recursive "$DOC_PUSH_PATH"/ s3://$DOXYGEN_S3_BUCKET/stack/doxygen/

              # prevent unbounded accumulation of doc builds
              rm -rf "$DOC_PUSH_PATH"
            '''
          }
        }
      } // stage('push docs')
    } // ws
  } // node
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
