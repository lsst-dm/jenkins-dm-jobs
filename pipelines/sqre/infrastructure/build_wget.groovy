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
  def hubRepo    = 'lsstsqre/wget'
  def githubRepo = 'lsst-sqre/docker-wget'
  def githubRef  = 'master'
  def pushLatest = params.LATEST

  def run = {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      image = docker.build("${hubRepo}", "--pull=true --no-cache .")
    }

    stage('push') {
      if (! params.NO_PUSH) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(githubRef)
          if (pushLatest) {
            image.push('latest')
          }
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
