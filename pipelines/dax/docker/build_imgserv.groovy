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
  def repo = 'webserv/imgserv'
  def tag = 'dax_latest'

  node('docker') {
    timeout(time: 1, unit: 'HOURS') {
        git([
          url: util.githubSlugToUrl('lsst/dax_imgserv'),
          branch: 'master'
        ])

        stage('build') {
            withEnv(["DOCKER_REPO=$repo"]) {
                util.bash './lsst-dm-ci/prod_image.sh'
             }
        } // build

        stage('test') {
            docker.image("${repo}:${tag}").inside {
                util.bash '/app/lsst-dm-ci/run_tests.sh'
            }
        } // test

        stage('publish') {
            docker.withRegistry('https://index.docker.io/v1/',
            'dockerhub-sqreadmin') {
                util.bash './lsst-dm-ci/pub_image.sh'
            }
        } // publish

    } // timeout
  } // node
} // notify.wrap
