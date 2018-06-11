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
  util.requireParams([
    'AWSCLI_VER',
    'LATEST',
    'NO_PUSH',
  ])

  String ver         = params.AWSCLI_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def dockerRepo = sqre.awscli.docker_repo
  def githubRepo = sqre.awscli.github_repo
  def githubRef  = sqre.awscli.github_ref

  def image = null

  def run = {
    stage('checkout') {
      git([
        url: "https://github.com/${githubRepo}",
        branch: githubRef,
      ])
    }

    stage('build') {
      // ensure base image is always up to date
      image = docker.build("${dockerRepo}", "--pull=true --no-cache --build-arg AWSCLI_VER=${ver} .")
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

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // node
} // notify.wrap
