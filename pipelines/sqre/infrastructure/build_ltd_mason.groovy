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
  util.requireParams(['LTD_MASON_VER'])

  def image      = null
  def hubRepo    = 'lsstsqre/ltd-mason'
  def githubRepo = 'lsst-sqre/ltd-mason'
  def githubRef  = 'master'
  def buildDir   = 'docker'
  def ver        = params.LTD_MASON_VER
  def pushLatest = params.LATEST


  def run = {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      dir(buildDir) {
        // ensure base image is always up to date
        image = docker.build("${hubRepo}", "--pull=true --no-cache --build-arg LTD_MASON_VER=${ver} .")
      }
    }

    stage('push') {
      if (! params.NO_PUSH) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(ver)
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
