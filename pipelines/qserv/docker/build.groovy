def notify = null
node {
  dir('jenkins-dm-jobs') {
    // XXX the git step seemed to blowup on a branch of '*/<foo>'
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs()
    ])
    notify = load 'pipelines/lib/notify.groovy'
  }
}

try {
  notify.started()

  node('docker') {
    dir('qserv') {
      git([
        url: 'https://github.com/lsst/qserv.git',
        branch: 'master'
      ])
    }

    build('1_build-image.sh -CD')
    build('2_update-dev-image.sh')
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

def build(String script) {
  stage(script) {
    docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
      dir('qserv/admin/tools/docker') {
        withEnv(['DOCKER_REPO=qserv/qserv']) {
          wrap([$class: 'AnsiColorBuildWrapper']) {
            sh "./$script"
          }
        }
      }
    }
  }
}
