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
  def retries = 3
  def repo = 'webserv/imgserv'
  def tag = 'dax_latest'

  def run = {
    dir('imgserv/lsst-dm-ci') {
        stage('build') {
            retry(retries) {
                build(repo)
            }
        } // build

        stage('test') {
            retry(retries) {
                docker.image("${repo}:${tag}").inside {
                    util.bash "./run_tests.sh"
                }
            }
        } // test

        stage('publish') {
            util.bash "./pub_image.sh"
        } // publish
    } // dir
  } // run

  node('docker') {
    timeout(time: 2, unit: 'HOURS') {
      dir('imgserv') {
        git([
          url: util.githubSlugToUrl('lsst/dax_imgserv'),
          branch: 'master'
        ])
      }
      run()
    } // timeout
  } // node
} // notify.wrap

def build(String repo) {
    docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
        withEnv(['DOCKER_REPO=$repo']) {
            util.bash "./prod_image.sh"
        }
    }
}
