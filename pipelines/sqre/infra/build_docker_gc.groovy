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
    'LATEST',
    'NO_PUSH',
  ])

  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def dgc             = sqre.docker_gc
  def dockerfile      = dgc.dockerfile
  def dockerRegistry  = dgc.docker_registry

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
      opt << '.'

      dir(buildDir) {
        // ensure base image is always up to date
        //#image = docker.build(dockerRepo, opt.join(' '))
        // XXX jenkins has trouble with multipart docker builds
        util.bash("docker build -t ${dockerRepo} ${opt.join(' ')}")
        image = docker.image(dockerRepo)
      }
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
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
