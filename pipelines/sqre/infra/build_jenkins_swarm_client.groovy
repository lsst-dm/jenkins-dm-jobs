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
    'SWARM_VER',
    'LATEST',
    'NO_PUSH',
  ])

  String ver         = params.SWARM_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def swarm           = sqre.jenkins_swarm_client
  def dockerfile      = swarm.dockerfile
  def dockerRegistry  = swarm.docker_registry

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

    // current we are producing a "generic" image which uses the uid/gids
    // historically used by LSST jenkins and an "ldfc". Hopefully, this is a
    // short term kludge that may be replaced with k8s' runAsGroup security
    // context.
    stage('build generic') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg JSWARM_VERSION=${ver}"
      opt << '.'

      dir(buildDir) {
        // ensure base image is always up to date
        //#image = docker.build(dockerRepo, opt.join(' '))
        // XXX jenkins has trouble with multipart docker builds
        util.bash("docker build -t ${dockerRepo} ${opt.join(' ')}")
        image = docker.image(dockerRepo)
      }
    }

    stage('push generic') {
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

    stage('build ldfc') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
		  opt << '--build-arg JSWARM_UID=48435'
      opt << '--build-arg JSWARM_GID=202'
      opt << '.'

      dir(buildDir) {
        // ensure base image is always up to date
        // XXX jenkins has trouble with multipart docker builds
        util.bash("docker build -t ${dockerRepo} ${opt.join(' ')}")
        image = docker.image(dockerRepo)
      }
    }

    stage('push ldfc') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          if (pushLatest) {
            image.push("${ver}-ldfc")
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
