properties([
  copyArtifactPermission('/release/*'),
]);

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
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
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
  Integer timelimit      = Integer.parseInt(params.TIMEOUT)
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
  if (params.SPLENV_REF) {
    splenvRef = params.SPLENV_REF
  }

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

  def newRegistryTags = []
  registryTags.each { name ->
    fixOSVersion = name.replaceFirst("7", "9")
    fixDistribName = fixOSVersion.replace("stack-lsst_distrib", "lsst_sitcom")
    newRegistryTags += fixDistribName
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
      opt << "--build-arg LSST_SPLENV_REF=\"${splenvRef}\""
      opt << "--load"
      opt << '.'

      dir(buildDir) {
        image = docker.build("${dockerRepo}", opt.join(' '))
        image2 = docker.build("panda-dev-1a74/${dockerRepo}", opt.join(' '))
        image3 = docker.build("lsstsqre/almalinux", opt.join(' '))
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
          newRegistryTags.each { name ->
            image3.push(name)
          }
        }
        docker.withRegistry(
          'https://us-central1-docker.pkg.dev/',
          'google_archive_registry_sa'
        ) {
          registryTags.each { name ->
            image2.push(name)
          }
        }
      }
    } // push
  } // run

  util.nodeWrap('docker') {
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
  } // util.nodeWrap
} // notify.wrap
