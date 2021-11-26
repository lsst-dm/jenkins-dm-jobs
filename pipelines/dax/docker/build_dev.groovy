node('jenkins-manager') {
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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.nodeWrap('docker') {
    timeout(time: 4, unit: 'HOURS') {
      dir('qserv') {
        git([
          url: util.githubSlugToUrl('lsst/qserv'),
          branch: 'qserv-classic'
        ])
      }

      build('dev_images.sh')
    } // timeout
  } // util.nodeWrap
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
