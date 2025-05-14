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
  def dockerTag      = "al9-${eupsTag}"
  def timestamp      = util.epochMilliToUtc(currentBuild.startTimeInMillis)
  def shebangtronUrl = util.shebangtronUrl()
  def dockerdigest   = []

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

    def image = null
    def repo  = null


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
        }
      }
      stage('push') {
        def digest = null
        // Should be removed once we drop dockerhub support
        def arch = lsstswConfig.display_name.tokenize('-').last()
        if (!noPush) {
          docker.withRegistry(
            'https://ghcr.io',
            'rubinobs-dm'
          ) {
            registryTags.each { name ->
              image.push(name + "_" + arch)
            }
          }
          docker.withRegistry(
            'https://us-central1-docker.pkg.dev/',
            'google_archive_registry_sa'
          ) {
            registryTags.each { name ->
              image2.push(name+"_"+arch)
            }
          }
          digest = sh(
            script: "docker inspect --format='{{index .RepoDigests 0}}' ${dockerRepo}:${dockerTag}_${arch}",
            returnStdout: true
          ).trim()

        }
          dockerdigest.add(digest)
      } // push

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
          image: "${ghdockerRepo}:${ghdockerTag}",
          docker_registry: [
            repo: ghdockerRepo,
            tag: ghdockerTag
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
    stage('digest'){
        def digest = dockerdigest.join(' ')
        docker.withRegistry(
          'https://ghcr.io',
          'rubinobs-dm'
        ) {
        registryTags.each { name ->
          sh(script: """ \
            docker buildx imagetools create -t $dockerRepo:$name \
            $digest
            """,
            returnStdout: true)
        }

        }
        docker.withRegistry(
          'https://us-central1-docker.pkg.dev/',
          'google_archive_registry_sa'
        ) {
          registryTags.each { name ->
            sh(script: """ \
              docker buildx imagetools create -t us-central1-docker.pkg.dev/panda-dev-1a74/$dockerRepo:$name \
              $digest
              """,
              returnStdout: true)
          }
        }
    }

  } // merge
  util.nodeWrap('linux-64') {
      timeout(time: timelimit, unit: 'HOURS') {
        merge()
      }
    } // nodeWrap

} // notify.wrap
