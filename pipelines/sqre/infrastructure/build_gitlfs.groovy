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
  }
}

notify.wrap {

  def image = null
  def hubRepo = 'lsstsqre/gitlfs'
  def githubRepo = 'lsst-sqre/docker-gitlfs'
  def githubRef  = 'master'

  node('docker') {
    stage('checkout') {
      git([
        url: "https://github.com/${github_repo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      docker.image('docker.io/centos:7').pull()

      image = docker.build("${hubRepo}", '--no-cache .')
    }

    stage('push') {
      if (! params.NO_PUSH) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push('latest')
        }
      }
    } // push
  } // node
} // notify.wrap
