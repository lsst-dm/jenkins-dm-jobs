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
  util.requireParams(['CODEKIT_VER'])

  def image      = null
  def regRepo    = 'lsstsqre/codekit'
  def githubRepo = 'lsst-sqre/sqre-codekit'
  def githubRef  = 'master'
  def buildDir   = 'docker'
  def codekitVer = params.CODEKIT_VER
  def pushLatest = params.LATEST

  node('docker') {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      dir(buildDir) {
        // ensure base image is always up to date
        image = docker.build("${regRepo}", "--pull=true --no-cache --build-arg CODEKIT_VER=${codekitVer} .")
      }
    }

    stage('push') {
      if (! params.NO_PUSH) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(codekitVer)
          if (pushLatest) {
            image.push('latest')
          }
        }
      }
    } // push
  } // node
} // notify.wrap
