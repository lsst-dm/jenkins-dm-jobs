node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
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

  def run = {
    stage('checkout') {
      git([
        url: githubRepo,
        branch: gitRef,
      ])
    }

    stage('build and push generic') {
      def cacheRepo = 'us-central1-docker.pkg.dev/prompt-proto/buildcache/jenkins-swarm-client'
      def metadataFile = '/tmp/build-metadata-generic.json'

      util.setupBuildkitBuilder()

      if (pushDocker) {
        withCredentials([usernamePassword(
          credentialsId: 'rubinobs-dm',
          usernameVariable: 'GHCR_USER',
          passwordVariable: 'GHCR_TOKEN',
        )]) {
          sh 'echo $GHCR_TOKEN | docker login ghcr.io -u $GHCR_USER --password-stdin'
        }
      }

      def buildArgs = [
        '--pull=true',
        "--build-arg JSWARM_VERSION=${ver}",
        util.buildkitCacheArgs(cacheRepo, 'amd64'),
        "--metadata-file ${metadataFile}",
      ]

      if (pushDocker) {
        buildArgs << '--push'
        buildArgs << "--tag ${dockerRepo}:${ver}"
        if (pushLatest) {
          buildArgs << "--tag ${dockerRepo}:latest"
        }
      }

      buildArgs << '.'

      dir(buildDir) {
        sh "docker buildx build ${buildArgs.join(' ')}"
      }
    }

    stage('build and push ldfc') {
      def cacheRepo = 'us-central1-docker.pkg.dev/prompt-proto/buildcache/jenkins-swarm-client'
      def metadataFile = '/tmp/build-metadata-ldfc.json'

      def buildArgs = [
        '--pull=true',
        '--build-arg JSWARM_UID=48435',
        '--build-arg JSWARM_GID=202',
        util.buildkitCacheArgs(cacheRepo, 'amd64-ldfc'),
        "--metadata-file ${metadataFile}",
      ]

      if (pushDocker) {
        buildArgs << '--push'
        if (pushLatest) {
          buildArgs << "--tag ${dockerRepo}:${ver}-ldfc"
        }
      }

      buildArgs << '.'

      dir(buildDir) {
        sh "docker buildx build ${buildArgs.join(' ')}"
      }
    }
  } // run

  util.nodeWrap('linux-64') {
    timeout(time: 30, unit: 'MINUTES') {
      run()
    }
  } // util.nodeWrap
} // notify.wrap
