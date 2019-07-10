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
    'NO_PUSH',
  ])

  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def hubRepo    = 'lsstsqre/tag-monger'
  def githubSlug = 'lsst-sqre/tag-monger'
  def githubRepo = "https://github.com/${githubSlug}"
  def gitRef     = 'master'
  def dockerDir  = ''

  def image = null

  def run = {
    def abbrHash = null

    stage('checkout') {
      git([
        url: githubRepo,
        branch: gitRef,
      ])

      abbrHash = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
    }

    stage('build') {
      dir(dockerDir) {
        // ensure base image is always up to date
        //image = docker.build(hubRepo, '--pull=true --no-cache .')
        // XXX for unknown reasons, jenkins is choking on this Dockerfile
        // running the build by hand seems to kludge around the problem...
        util.bash("docker build -t ${hubRepo} --pull=true --no-cache .")
        image = docker.image(hubRepo)
      }
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(gitRef)
          if (gitRef == 'master') {
            image.push("g${abbrHash}")
          }
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
