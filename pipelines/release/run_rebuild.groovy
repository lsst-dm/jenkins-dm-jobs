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

  node('lsst-dev') {
    ws('/home/lsstsw/jenkins/release') {
      stage('build') {
        try {
          dir('lsstsw') {
            git([
              url: 'https://github.com/lsst/lsstsw.git',
              branch: 'master',
              changelog: false,
              poll: false
            ])
          }

          dir('buildbot-scripts') {
            git([
              url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
              branch: 'master',
              changelog: false,
              poll: false
            ])
          }

          def env = [
            "EUPS_PKGROOT=${pwd()}/distrib",
            'VERSIONDB_REPO=git@github.com:lsst/versiondb.git',
            'VERSIONDB_PUSH=true',
            "WORKSPACE=${pwd()}",
          ]

          withCredentials([[
            $class: 'StringBinding',
            credentialsId: 'cmirror-s3-bucket',
            variable: 'CMIRROR_S3_BUCKET'
          ]]) {
            withEnv(env) {
              sshagent (credentials: ['github-jenkins-versiondb']) {
                util.shColor '''
                  #!/bin/bash -e

                  # ensure that we are using the lsstsw clone relative to the workspace
                  # and that another value for LSSTSW isn't leaking in from the env
                  export LSSTSW="${WORKSPACE}/lsstsw"

                  # isolate eups cache files
                  export EUPS_USERDATA="${WORKSPACE}/.eups"

                  if [[ -e "${WORKSPACE}/REPOS" ]]; then
                    export REPOSFILE="${WORKSPACE}/REPOS"
                  fi

                  ./buildbot-scripts/jenkins_wrapper.sh

                  # handled by the postbuild on failure script if there is an error
                  rm -rf "${WORKSPACE}/REPOS"
                '''
              }
            }
          } // withCredentials([[
        } finally {
          withEnv(["WORKSPACE=${pwd()}"]) {
            util.shColor '''
              if hash lsof 2>/dev/null; then
                Z=$(lsof -d 200 -t)
                if [[ ! -z $Z ]]; then
                  kill -9 $Z
                fi
              else
                echo "lsof is missing; unable to kill rebuild related processes."
              fi

              rm -rf "${WORKSPACE}/lsstsw/stack/.lockDir"
              rm -rf "${WORKSPACE}/REPOS"
            '''
          }

          archiveArtifacts([
            artifacts: "lsstsw/build/manifest.txt",
            fingerprint: true
          ])
        } // try
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
          withEnv(["WORKSPACE=${pwd()}"]) {
            util.shColor '''
              #!/bin/bash -e

              if [[ $SKIP_DOCS == "true" ]]; then
                exit 0
              fi

              # setup python env
              . "${WORKSPACE}/lsstsw/bin/setup.sh"
              pip install awscli

              # provides DOC_PUSH_PATH
              . ./buildbot-scripts/settings.cfg.sh

              aws s3 sync "$DOC_PUSH_PATH"/ s3://$DOXYGEN_S3_BUCKET/stack/doxygen/
            '''
          }
        }
      } // stage('push docs')
    } // ws('/home/lsstsw/jenkins/release')
  } // node('lsst-dev')
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
