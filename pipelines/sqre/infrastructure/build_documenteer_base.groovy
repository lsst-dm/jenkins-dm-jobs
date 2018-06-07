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
  def hubRepo    = 'lsstsqre/documenteer-base'
  def githubSlug = 'lsst-sqre/docker-documenteer-base'
  def githubRepo = "https://github.com/${githubSlug}"
  def githubRef  = 'master'
  def dockerDir  = ''
  def pushLatest = params.LATEST
  def noPush     = params.NO_PUSH

  def run = {
    def abbrHash = null

    stage('checkout') {
      git([
        url: githubRepo,
        branch: githubRef,
      ])

      abbrHash = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
    }

    stage('build') {
      dir(dockerDir) {
        // ensure base image is always up to date
        //image = docker.build("${hubRepo}", '--pull=true --no-cache .')
        // XXX for unknown reasons, jenkins is choking on this Dockerfile
        // running the build by hand seems to kludge around the problem...
        util.bash("docker build -t ${hubRepo} --pull=true --no-cache .")
        image = docker.image(hubRepo)
      }
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          image.push(githubRef)
          if (githubRef == 'master') {
            image.push("g${abbrHash}")
          }
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
