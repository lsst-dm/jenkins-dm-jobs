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
  def image      = null
  def hubRepo    = 'lsstsqre/postqa'
  def githubRepo = 'lsst-sqre/docker-postqa'
  def githubRef  = 'master'
  def postqaVer  = '1.3.3'

  def run = {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      docker.image('centos:7').pull()

      image = docker.build("${hubRepo}", "--no-cache --build-arg POSTQA_VER=${postqaVer} .")
    }

    stage('push') {
      if (! params.NO_PUSH) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(postqaVer)
        }
      }
    } // push
  } // run

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }

  } // node
} // notify.wrap
