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
    scipipe = util.scipipeConfig()
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

  def newinstall     = scipipe.newinstall
  def dockerfile     = newinstall.dockerfile
  def dockerRegistry = newinstall.docker_registry

  def githubRepo    = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef        = dockerfile.git_ref
  def buildDir      = dockerfile.dir
  def dockerRepo    = dockerRegistry.repo
  def newinstallUrl = util.newinstallUrl()

  def baseDockerRepo = sqre.layercake.docker_registry.repo
  def baseDockerTag  = '7-stackbase-devtoolset-6'
  def baseImage      = "${baseDockerRepo}:${baseDockerTag}"
  def splenvRef      = scipipe.canonical.lsstsw_config.splenv_ref

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
      withCredentials([[
        $class: 'StringBinding',
        credentialsId: 'eups-url',
        variable: 'EUPS_URL'
      ]]) {
        opt << "--build-arg LSST_EUPS_PKGROOT_BASE_URL=\"${EUPS_URL}/stack\""
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
          image.push(splenvRef)
          image.push("${splenvRef}-${util.sanitizeDockerTag(gitRef)}")
          if (gitRef == 'master') {
            image.push("${splenvRef}-g${abbrHash}")
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
