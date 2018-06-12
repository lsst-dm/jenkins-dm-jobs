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
    config = util.readYamlFile 'etc/science_pipelines/build_matrix.yaml'
  }
}

notify.wrap {
  util.requireParams([
    'NO_PUSH',
    'PRODUCT',
    'TAG',
    'TIMEOUT',
  ])

  String product    = params.PRODUCT
  String eupsTag    = params.TAG
  Boolean noPush    = params.NO_PUSH
  Integer timelimit = params.TIMEOUT

  def scipipe     = config.scipipe_release
  def dockerfile  = scipipe.dockerfile
  def docker      = scipipe.docker
  def newinstall  = config.newinstall
  def shebangtron = config.shebangtron

  def githubRepo = util.githubSlugToUrl(dockerfile.github_repo, 'https')
  def githubRef  = dockerfile.git_ref
  def dockerRepo = docker.repo
  def dockerTag  = "7-stack-lsst_distrib-${eupsTag}"
  def timestamp  = util.epochMilliToUtc(currentBuild.startTimeInMillis)

  def newinstallImage = newinstall.docker.repo
  newinstallImage += ":${newinstall.docker.tag}"

  def image = null
  def repo  = null

  def run = {
    stage('checkout') {
      repo = git([
        url: githubRepo,
        branch: githubRef,
      ])
    }

    stage('build') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg EUPS_PRODUCT=\"${product}\""
      opt << "--build-arg EUPS_TAG=\"${tag}\""
      opt << "--build-arg DOCKERFILE_GIT_BRANCH=\"${repo.GIT_BRANCH}\""
      opt << "--build-arg DOCKERFILE_GIT_COMMIT=\"${repo.GIT_COMMIT}\""
      opt << "--build-arg DOCKERFILE_GIT_URL=\"${repo.GIT_URL}\""
      opt << "--build-arg JENKINS_JOB_NAME=\"${env.JOB_NAME}\""
      opt << "--build-arg JENKINS_BUILD_ID=\"${env.BUILD_ID}\""
      opt << "--build-arg JENKINS_BUILD_URL=\"${env.RUN_DISPLAY_URL}\""
      opt << "--build-arg BASE_IMAGE=\"${newinstallImage}\""
      opt << "--build-arg SHEBANGTRON_URL=\"${shebangtron.url}\""
      opt << '.'

      image = docker.build("${dockerRepo}", opt.join(' '))
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          [dockerTag, "${dockerTag}-${timestamp}"].each { name ->
            image.push(name)
          }
        }
      }
    } // push
  } // run

  node('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
