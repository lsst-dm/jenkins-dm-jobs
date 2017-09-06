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

  node('docker') {
    dir('qserv') {
      git([
        url: 'https://github.com/lsst/qserv.git',
        branch: 'master'
      ])
    }

    build('dev_images.sh')
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
      dir('qserv/admin/tools/docker/lsst-dm-ci') {
        withEnv(['DOCKER_REPO=qserv/qserv']) {
          util.shColor "./$script"
        }
      }
    }
  }
}
