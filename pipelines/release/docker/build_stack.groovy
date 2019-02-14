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
    scipipe = util.scipipeConfig()
  }
}

notify.wrap {
  util.requireParams([
    'EUPS_TAG',
    'NO_PUSH',
    'PRODUCTS',
    'TIMEOUT',
  ])

  String eupsTag         = params.EUPS_TAG
  String products        = params.PRODUCTS
  Boolean noPush         = params.NO_PUSH
  Integer timelimit      = params.TIMEOUT
  String extraDockerTags = params.DOCKER_TAGS

  // optional
  String manifestId   = params.MANIFEST_ID ?: ''
  String lsstCompiler = params.LSST_COMPILER ?: ''

  def release        = scipipe.scipipe_release
  def dockerfile     = release.dockerfile
  def dockerRegistry = release.docker_registry
  def newinstall     = scipipe.newinstall

  def githubRepo     = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef         = dockerfile.git_ref
  def buildDir       = dockerfile.dir
  def dockerRepo     = dockerRegistry.repo
  def dockerTag      = "7-stack-lsst_distrib-${eupsTag}"
  def timestamp      = util.epochMilliToUtc(currentBuild.startTimeInMillis)
  def shebangtronUrl = util.shebangtronUrl()

  def newinstallImage = newinstall.docker_registry.repo
  def splenvRef       = scipipe.canonical.lsstsw_config.splenv_ref
  def baseImage       = "${newinstallImage}:${splenvRef}"

  def image = null
  def repo  = null

  def registryTags = [
    dockerTag,
    "${dockerTag}-${timestamp}",
  ]

  if (extraDockerTags) {
    // manual constructor is needed "because java"
    registryTags += Arrays.asList(extraDockerTags.split())
  }

  def run = {
    stage('checkout') {
      repo = git([
        url: githubRepo,
        branch: gitRef,
      ])
    }

    stage('build') {
      def opt = []
      // ensure base image is always up to date
      opt << '--pull=true'
      opt << '--no-cache'
      opt << "--build-arg EUPS_PRODUCTS=\"${products}\""
      opt << "--build-arg EUPS_TAG=\"${eupsTag}\""
      opt << "--build-arg DOCKERFILE_GIT_BRANCH=\"${repo.GIT_BRANCH}\""
      opt << "--build-arg DOCKERFILE_GIT_COMMIT=\"${repo.GIT_COMMIT}\""
      opt << "--build-arg DOCKERFILE_GIT_URL=\"${repo.GIT_URL}\""
      opt << "--build-arg JENKINS_JOB_NAME=\"${env.JOB_NAME}\""
      opt << "--build-arg JENKINS_BUILD_ID=\"${env.BUILD_ID}\""
      opt << "--build-arg JENKINS_BUILD_URL=\"${env.RUN_DISPLAY_URL}\""
      opt << "--build-arg BASE_IMAGE=\"${baseImage}\""
      opt << "--build-arg SHEBANGTRON_URL=\"${shebangtronUrl}\""
      opt << "--build-arg VERSIONDB_MANIFEST_ID=\"${manifestId}\""
      opt << "--build-arg LSST_COMPILER=\"${lsstCompiler}\""
      opt << '.'

      dir(buildDir) {
        image = docker.build("${dockerRepo}", opt.join(' '))
      }
    }

    stage('push') {
      if (!noPush) {
        docker.withRegistry(
          'https://index.docker.io/v1/',
          'dockerhub-sqreadmin'
        ) {
          registryTags.each { name ->
            image.push(name)
          }
        }
      }
    } // push
  } // run

  node('docker') {
    try {
      timeout(time: timelimit, unit: 'HOURS') {
        run()
      }
    } finally {
      stage('archive') {
        def resultsFile = 'results.json'

        util.dumpJson(resultsFile,  [
          base_image: baseImage ?: null,
          image: "${dockerRepo}:${dockerTag}",
          docker_registry: [
            repo: dockerRepo,
            tag: dockerTag
          ],
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      } // stage
    } // try
  } // node
} // notify.wrap
