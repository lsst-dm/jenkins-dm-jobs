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
      dir('imgserv') {
        git([
          url: util.githubSlugToUrl('lsst/dax_imgserv'),
          branch: 'master'
        ])
      }

      build('prod_image.sh')
    } // timeout
  } // node
} // notify.wrap

def build(String script) {
  stage(script) {
    docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
      dir('imgserv/lsst-dm-ci') {
        withEnv(['DOCKER_REPO=webserv/imgserv']) {
          util.bash "./$script"
        }
      }
    }
  }
}
