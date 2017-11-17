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
  util.requireParams(['LFS_VER'])

  def image      = null
  def hubRepo    = 'lsstsqre/gitlfs'
  def githubRepo = 'lsst-sqre/docker-gitlfs'
  def githubRef  = 'master'
  def lfsVer     = params.LFS_VER
  def pushLatest = params.LATEST

  node('docker') {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      image = docker.build("${hubRepo}", "--pull=true --no-cache --build-arg LFS_VER=${lfsVer} .")
    }

    stage('push') {
      if (! params.NO_PUSH) {
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
  } // node
} // notify.wrap
