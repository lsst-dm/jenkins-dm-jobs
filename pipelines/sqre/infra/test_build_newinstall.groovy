node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util_test.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'LATEST',
    'NO_PUSH',
    'SPLENV_REF',
    'RUBINENV_VER',
  ])

  Boolean pushLatest = params.LATEST
  Boolean pushDocker = (! params.NO_PUSH.toBoolean())

  def newinstall     = scipipe.newinstall
  def dockerfile     = newinstall.dockerfile
  def dockerRegistry = newinstall.docker_registry

  def githubRepo    = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef        = dockerfile.git_ref
  def buildDir      = dockerfile.dir
  def dockerRepo    = dockerRegistry.repo
  def newinstallUrl = util.newinstallUrl()

  def baseDockerRepo = sqre.scipipe_base.docker_registry.repo
  def baseDockerTag  = '7'
  def baseImage      = "${baseDockerRepo}:${baseDockerTag}"
  def splenvRef      = params.SPLENV_REF
  def rubinEnvVer    = params.RUBINENV_VER

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
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg BASE_IMAGE=\"${baseImage}\""
      opt << "--build-arg NEWINSTALL_URL=\"${newinstallUrl}\""
      opt << "--build-arg LSST_SPLENV_REF=\"${splenvRef}\""
      opt << "--build-arg LSST_CONDA_ENV_NAME=\"lsst-scipipe-${rubinEnvVer}\""
      withCredentials([[
        $class: 'StringBinding',
        credentialsId: 'eups-url',
        variable: 'EUPS_URL'
      ]]) {
        opt << "--build-arg LSST_EUPS_PKGROOT_BASE_URL=\"${EUPS_URL}/stack_test\""
      }
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
          image.push(rubinEnvVer)
          image.push("${rubinEnvVer}-${util.sanitizeDockerTag(gitRef)}")
          if (gitRef == 'master') {
            image.push("${rubinEnvVer}-g${abbrHash}")
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
