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
    sqre = util.readYamlFile 'etc/sqre/config.yaml'
  }
}

notify.wrap {
  util.requireParams(['CODEKIT_VER'])

  def image      = null
  def dockerRepo = sqre.codekit.docker_repo
  def githubRepo = sqre.codekit.github_repo
  def githubRef  = sqre.codekit.github_ref
  def buildDir   = 'docker'
  def codekitVer = params.CODEKIT_VER
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
        image = docker.build("${dockerRepo}", "--pull=true --no-cache --build-arg CODEKIT_VER=${codekitVer} .")
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
  } // run

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // node
} // notify.wrap
