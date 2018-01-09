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
  def hubRepo    = 'lsstsqre/gitlfs-server'
  def githubSlug = 'lsst-sqre/git-lfs-s3-server'
  def githubRepo = "https://github.com/${githubSlug}"
  def githubRef  = 'master'
  def dockerDir  = 'docker'
  def pushLatest = params.LATEST

  def run = {
    stage('checkout') {
      git([
        url: githubRepo,
        branch: githubRef,
      ])
    }

    stage('build') {
      dir(dockerDir) {
        // ensure base image is always up to date
        image = docker.build("${hubRepo}", "--pull=true --no-cache --build-arg REPO=${githubRepo} --build-arg REF=${githubRef} .")
      }
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
  } // run

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // node
} // notify.wrap
