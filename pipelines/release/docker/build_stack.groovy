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
    'PRODUCT',
    'TAG',
    'TIMEOUT',
  ])

  def product   = params.PRODUCT
  def eupsTag   = params.TAG
  def noPush    = params.NO_PUSH
  def timelimit = params.TIMEOUT.toInteger()

  def image     = null
  def repo      = null
  def hubRepo   = config.release_docker_repo
  def hubTag    = "7-stack-lsst_distrib-${eupsTag}"
  def timestamp = util.epochMilliToUtc(currentBuild.startTimeInMillis)

  def run = {
    stage('checkout') {
      repo = git([
        url: 'https://github.com/lsst-sqre/docker-tarballs',
        branch: 'master'
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
      opt << "--build-arg BASE_IMAGE=\"lsstsqre/newinstall:latest\""
      opt << '.'

      image = docker.build("${hubRepo}", opt.join(' '))
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          [hubTag, "${hubTag}-${timestamp}"].each { name ->
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
