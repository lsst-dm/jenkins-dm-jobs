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
      stage('publish') {
        dir('lsstsw') {
          git([
            url: 'https://github.com/lsst/lsstsw.git',
            branch: 'master'
          ])
        }

        dir('buildbot-scripts') {
          git([
            url: 'https://github.com/lsst-sqre/buildbot-scripts.git',
            branch: 'master'
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
            util.shColor '''
              #!/bin/bash -e

              # ensure that we are using the lsstsw clone relative to the workspace
              # and that another value for LSSTSW isn't leaking in from the env
              export LSSTSW="${WORKSPACE}/lsstsw"

              # isolate eups cache files
              export EUPS_USERDATA="${WORKSPACE}/.eups"

              ARGS=()
              ARGS+=('-b' "$BUILD_ID")
              ARGS+=('-t' "$TAG")
              # split whitespace separated EUPS products into separate array elements
              # by not quoting
              ARGS+=($PRODUCT)

              export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"

              # setup.sh will unset $PRODUCTS
              source ./lsstsw/bin/setup.sh

              publish "${ARGS[@]}"
            '''
          }
        }// withCredentials([[
      } // stage('publish')

      stage('push packages') {
        withCredentials([[
          $class: 'UsernamePasswordMultiBinding',
          credentialsId: 'aws-eups-push',
          usernameVariable: 'AWS_ACCESS_KEY_ID',
          passwordVariable: 'AWS_SECRET_ACCESS_KEY'
        ],
        [
          $class: 'StringBinding',
          credentialsId: 'eups-push-bucket',
          variable: 'EUPS_S3_BUCKET'
        ]]) {
          def env = [
            "EUPS_PKGROOT=${pwd()}/distrib",
            "WORKSPACE=${pwd()}",
          ]

          withEnv(env) {
            util.shColor '''
              #!/bin/bash -e

              # setup python env
              . "${WORKSPACE}/lsstsw/bin/setup.sh"
              pip install awscli

              aws s3 sync "$EUPS_PKGROOT"/ s3://$EUPS_S3_BUCKET/stack/src/
            '''
          }
        }
      } // stage('push packages')
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
