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
  def timelimit = params.TIMEOUT.toInteger()

  def run = {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/jupyterlabdemo',
        branch: 'master'
      ])
    }

    // ensure the current image is used
    stage('docker pull') {
      docImage = "${params.BASE_IMAGE}:${params.TAG_PREFIX}${params.TAG}"
      docker.image(docImage).pull()
    }

    stage('build+push') {
      dir('jupyterlab') {
        if (! params.NO_PUSH) {
          docker.withRegistry(
            'https://index.docker.io/v1/',
            'dockerhub-sqreadmin'
          ) {
            util.bash """
              ./bld \
               -p '${params.PYVER}' \
               -b '${params.BASE_IMAGE}' \
               -n '${params.IMAGE_NAME}' \
               -t '${params.TAG_PREFIX}' \
               '${params.TAG}'
            """
          }
        } else {
          util.bash """
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
  } // node
} // notify.wrap
