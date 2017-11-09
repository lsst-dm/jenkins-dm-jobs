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

  def timelimit = params.TIMEOUT.toInteger()

  def run = {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/jupyterlabdemo',
        branch: 'master'
      ])
    }

    stage('build+push') {
      dir('jupyterlab') {
        if (! params.NO_PUSH) {
          docker.withRegistry(
            'https://index.docker.io/v1/',
            'dockerhub-sqreadmin'
          ) {
            util.shColor """
              ./bld \
               -p '${params.PYVER}' \
               -b '${params.BASE_IMAGE}' \
               -n '${params.IMAGE_NAME}' \
               -t '${params.TAG_PREFIX}' \
               '${params.TAG}'
            """
          }
        } else {
          util.shColor """
              ./bld \
               -d \
               -p '${params.PYVER}' \
               -b '${params.BASE_IMAGE}' \
               -n '${params.IMAGE_NAME}' \
               -t '${params.TAG_PREFIX}' \
               '${params.TAG}'
              docker build .
          """
        }
      }
    }
  } // run

  node('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
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
      pingAdamAborted()
      break
    case 'FAILURE':
      notify.failure()
      pingAdamFailure()
      break
    default:
      notify.failure()
      pingAdamFailure()
  }
}

def void pingAdam(String msg) {
  slackSend color: 'danger', message: "<@U2VGYJN92> ${msg)}"
}

def void pingAdamAborted() {
  pingAdam(notify.slackAbortedMessage()
}

def void pingAdamFailure() {
  pingAdam(notify.slackFailureMessage()
}
