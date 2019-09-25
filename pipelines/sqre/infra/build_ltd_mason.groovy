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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'LATEST',
    'LTD_MASON_VER',
    'NO_PUSH',
  ])

  Boolean pushLatest = params.LATEST
  String ver         = params.LTD_MASON_VER
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def hubRepo    = 'lsstsqre/ltd-mason'
  def githubRepo = 'lsst-sqre/ltd-mason'
  def gitRef     = 'master'
  def buildDir   = 'docker'

  def image = null

  def run = {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: gitRef,
      ])
    }

    stage('build') {
      dir(buildDir) {
        // ensure base image is always up to date
        image = docker.build("${hubRepo}", "--pull=true --no-cache --build-arg LTD_MASON_VER=${ver} .")
      }
    }

    stage('push') {
      if (pushDocker) {
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

  util.nodeWrap('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
