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
  node('docker') {
    timeout(time: 4, unit: 'HOURS') {
      dir('qserv') {
        git([
          url: 'https://github.com/lsst/qserv.git',
          branch: 'master'
        ])
      }

      build('dev_images.sh')
    } // timeout
  } // node
} // notify.wrap

def build(String script) {
  stage(script) {
    docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
      dir('qserv/admin/tools/docker/lsst-dm-ci') {
        withEnv(['DOCKER_REPO=qserv/qserv']) {
          util.bash "./$script"
        }
      }
    }
  }
}
