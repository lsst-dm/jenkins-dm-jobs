node {
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
    'NO_PUSH',
  ])

  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def hubRepo    = 'lsstsqre/wget'
  def githubRepo = 'lsst-sqre/docker-wget'
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
      image = docker.build(hubRepo, "--pull=true --no-cache .")
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(gitRef)
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
