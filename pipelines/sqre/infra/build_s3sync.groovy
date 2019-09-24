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
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'AWSCLI_VER',
    'LATEST',
    'PUBLISH',
  ])

  String awscliVer   = params.AWSCLI_VER
  Boolean pushLatest = params.LATEST
  Boolean pushDocker = params.PUBLISH

  def s3sync          = sqre.s3sync
  def dockerfile      = s3sync.dockerfile
  def dockerRegistry  = s3sync.docker_registry

  def githubRepo = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef     = dockerfile.git_ref
  def buildDir   = dockerfile.dir
  def dockerRepo = dockerRegistry.repo

  def image    = null
  def abbrHash = null

  def run = {
    stage('checkout') {
      git([
        url: githubRepo,
        branch: gitRef,
      ])

      abbrHash = sh(
        returnStdout: true,
        script: "git log -n 1 --pretty=format:'%h'",
      ).trim()
    }

    stage('build') {
      def opt = []
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg AWSCLI_VER=${awscliVer}"
      opt << '.'

      dir(buildDir) {
        image = docker.build(dockerRepo, opt.join(' '))
      }
    }

    stage('push') {
      if (pushDocker) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          def safeRef = util.sanitizeDockerTag(gitRef)
          [safeRef, "${safeRef}-g${abbrHash}"].each { tag ->
            image.push(tag)
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
