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
  util.requireParams([
    'LATEST',
    'LFS_VER',
    'NO_PUSH',
  ])

  String lfsVer      = params.LFS_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = params.NO_PUSH

  def hubRepo    = 'lsstsqre/gitlfs'
  def githubRepo = 'lsst-sqre/docker-gitlfs'
  def gitRef     = 'master'

  def image = null

  def run = {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: gitRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      image = docker.build("${hubRepo}", "--pull=true --no-cache --build-arg LFS_VER=${lfsVer} .")
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(lfsVer)
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
