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
            util.shColor "./bld -p '${params.PYVER}' -b '${params.BASE_IMAGE}' -n '${params.IMAGE_NAME}' -t '${params.TAG_PREFIX}' '${params.TAG}'"
          }
        } else {
          util.shColor """
            ./bld -d '${params.PYVER}' -b '${params.BASE_IMAGE}' -n '${params.IMAGE_NAME}' -t '${params.TAG_PREFIX}' '${params.TAG}'
            docker build .
          """
        }
      }
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
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}
