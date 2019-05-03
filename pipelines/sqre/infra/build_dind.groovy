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
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'DIND_VER',
    'LATEST',
    'NO_PUSH',
  ])

  String ver         = params.DIND_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def dind            = sqre.dind
  def dockerfile      = dind.dockerfile
  def dockerRegistry  = dind.docker_registry

  def githubRepo = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef     = dockerfile.git_ref
  def buildDir   = dockerfile.dir
  def dockerRepo = dockerRegistry.repo

  def image = null

  def run = {
    stage('checkout') {
      git([
        url: githubRepo,
        branch: gitRef,
      ])
    }

    stage('build') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg BASE_IMAGE=docker:${ver}-dind"
      opt << '.'

      dir(buildDir) {
        // ensure base image is always up to date
        image = docker.build(dockerRepo, opt.join(' '))
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

  node('docker') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // node
} // notify.wrap
