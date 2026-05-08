properties([
  copyArtifactPermission('/release/*'),
]);

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


  def build_stack    = scipipe.build_stack
  def lsstswConfigs  = build_stack.lsstsw_config
  def release        = scipipe.scipipe_release
  def dockerfile     = release.dockerfile
  def dockerRegistry = release.docker_registry
  def newinstall     = scipipe.newinstall

  def githubRepo     = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef         = dockerfile.git_ref
  def buildDir       = dockerfile.dir
  def dockerRepo     = dockerRegistry.repo
  def gcpRepo        = dockerRegistry.repo
  def dockerTag      = "al9-${eupsTag}"
  def timestamp      = util.epochMilliToUtc(currentBuild.startTimeInMillis)
  def shebangtronUrl = util.shebangtronUrl()
  def dockerdigest   = []

  if (dockerRegistry.ghcr) {
      dockerRepo = "ghcr.io/${dockerRepo}"
  }
  def registryTags = [
    dockerTag,
    "${dockerTag}-${timestamp}",
  ]

  if (extraDockerTags) {
    // manual constructor is needed "because java"
    registryTags += Arrays.asList(extraDockerTags.split())
    def extraTagList = Arrays.asList(extraDockerTags.split())
    extraTagList.each { tag ->
    registryTags += "al9-${tag}"
    }
  }


  def newRegistryTags = []
  registryTags.each { name ->
    fixOSVersion = name.replaceFirst("7", "9")
    fixDistribName = fixOSVersion.replace("stack-lsst_distrib", "lsst_sitcom")
    newRegistryTags += fixDistribName
  }

  def matrix = [:]
  lsstswConfigs.each{ lsstswConfig ->
    def slug = util.lsstswConfigSlug(lsstswConfig)
    matrix[slug] ={

    def newinstallImage = newinstall.docker_registry.repo
    def newinstallTagBase = newinstall.docker_registry.tag
    def splenvRef       = lsstswConfig.splenv_ref
    if (params.SPLENV_REF) {
      splenvRef = params.SPLENV_REF
    }

    def baseImage       = "${newinstallImage}:${newinstallTagBase}-${splenvRef}"

    def repo  = null


    def run = {
      stage('checkout') {
        repo = git([
          url: githubRepo,
          branch: gitRef,
        ])
      }

      stage('build and push') {
        def arch = lsstswConfig.display_name.tokenize('-').last()
        def cacheRepo = 'us-central1-docker.pkg.dev/prompt-proto/buildcache/scipipe-base'
        def metadataFile = '/tmp/build-metadata.json'

        util.setupBuildkitBuilder()

        if (!noPush) {
          withCredentials([usernamePassword(
            credentialsId: 'rubinobs-dm',
            usernameVariable: 'GHCR_USER',
            passwordVariable: 'GHCR_TOKEN',
          )]) {
            sh 'echo $GHCR_TOKEN | docker login ghcr.io -u $GHCR_USER --password-stdin'
          }
          withCredentials([file(
            credentialsId: 'google_archive_registry_sa',
            variable: 'GOOGLE_APPLICATION_CREDENTIALS',
          )]) {
            sh 'gcloud auth activate-service-account --key-file=$GOOGLE_APPLICATION_CREDENTIALS'
            sh 'gcloud auth configure-docker us-central1-docker.pkg.dev --quiet'
          }
        }

        def buildArgs = [
          '--pull=true',
          "--build-arg EUPS_PRODUCTS=\"${products}\"",
          "--build-arg EUPS_TAG=\"${eupsTag}\"",
          "--build-arg DOCKERFILE_GIT_BRANCH=\"${repo.GIT_BRANCH}\"",
          "--build-arg DOCKERFILE_GIT_COMMIT=\"${repo.GIT_COMMIT}\"",
          "--build-arg DOCKERFILE_GIT_URL=\"${repo.GIT_URL}\"",
          "--build-arg JENKINS_JOB_NAME=\"${env.JOB_NAME}\"",
          "--build-arg JENKINS_BUILD_ID=\"${env.BUILD_ID}\"",
          "--build-arg JENKINS_BUILD_URL=\"${env.RUN_DISPLAY_URL}\"",
          "--build-arg BASE_IMAGE=\"${baseImage}\"",
          "--build-arg SHEBANGTRON_URL=\"${shebangtronUrl}\"",
          "--build-arg VERSIONDB_MANIFEST_ID=\"${manifestId}\"",
          "--build-arg LSST_COMPILER=\"${lsstCompiler}\"",
          "--build-arg LSST_SPLENV_REF=\"${splenvRef}\"",
          util.buildkitCacheArgs(cacheRepo, arch),
          "--metadata-file ${metadataFile}",
        ]

        if (!noPush) {
          buildArgs << '--push'
          registryTags.each { name ->
            buildArgs << "--tag ${dockerRepo}:${name}_${arch}"
            buildArgs << "--tag us-central1-docker.pkg.dev/prompt-proto/${gcpRepo}:${name}_${arch}"
          }
        }

        buildArgs << '.'

        dir(buildDir) {
          sh "docker buildx build ${buildArgs.join(' ')}"
        }

        if (!noPush) {
          def meta = readJSON(file: metadataFile)
          def digest = meta['containerimage.digest']
          dockerdigest.add("${dockerRepo}:${dockerTag}_${arch}@${digest}")
        }
      } // stage('build and push')

  } // run

  util.nodeWrap(lsstswConfig.label) {
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
  }
  }
  parallel matrix

  def merge = {
    stage('digest') {
      withCredentials([usernamePassword(
        credentialsId: 'rubinobs-dm',
        usernameVariable: 'GHCR_USER',
        passwordVariable: 'GHCR_TOKEN',
      )]) {
        sh 'echo $GHCR_TOKEN | docker login ghcr.io -u $GHCR_USER --password-stdin'
      }
      withCredentials([file(
        credentialsId: 'google_archive_registry_sa',
        variable: 'GOOGLE_APPLICATION_CREDENTIALS',
      )]) {
        sh 'gcloud auth activate-service-account --key-file=$GOOGLE_APPLICATION_CREDENTIALS'
        sh 'gcloud auth configure-docker us-central1-docker.pkg.dev --quiet'
      }

      def digest = dockerdigest.join(' ')
      registryTags.each { name ->
        sh "docker buildx imagetools create -t ${dockerRepo}:${name} ${digest}"
        sh "docker buildx imagetools create -t us-central1-docker.pkg.dev/prompt-proto/${gcpRepo}:${name} ${digest}"
      }
    }
  } // merge

  if (!noPush && !dockerdigest.isEmpty()) {
    util.nodeWrap('linux-64') {
      timeout(time: timelimit, unit: 'HOURS') {
        merge()
      }
    } // nodeWrap
  }

} // notify.wrap
