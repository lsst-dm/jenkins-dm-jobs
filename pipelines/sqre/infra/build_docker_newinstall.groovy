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
    'NO_PUSH',
    'SPLENV_REF',
  ])
  def build_stack    = scipipe.build_stack
  def lsstswConfigs  = build_stack.lsstsw_config
  def release        = scipipe.newinstall
  def dockerfile     = release.dockerfile
  def githubRepo     = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef         = dockerfile.git_ref
  def buildDir       = dockerfile.dir
  def dockerRepo     = release.docker_registry.repo
  def dockerTag      = release.docker_registry.tag
  def dockerdigest   = []

  Boolean noPush         = params.NO_PUSH



  def splenvRef       = params.SPLENV_REF
  def registryTags = [
    dockerTag,
    "latest",
    "$dockerTag-$splenvRef",
  ]
  def matrix = [:]
  lsstswConfigs.each{ lsstswConfig ->
    def slug = util.lsstswConfigSlug(lsstswConfig)
    matrix[slug] = {
    def run = {
      stage('checkout') {
        repo = git([
          url: githubRepo,
          branch: gitRef,
        ])
      }

      stage('build and push') {
        def arch = lsstswConfig.display_name.tokenize('-').last()
        def cacheRepo = 'us-central1-docker.pkg.dev/prompt-proto/buildcache/newinstall'
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
        }

        def buildArgs = [
          '--pull=true',
          "--build-arg LSST_SPLENV_REF=\"${splenvRef}\"",
          util.buildkitCacheArgs(cacheRepo, arch),
          "--metadata-file ${metadataFile}",
        ]

        if (!noPush) {
          buildArgs << '--push'
          registryTags.each { name ->
            buildArgs << "--tag ${dockerRepo}:${name}"
          }
        }

        buildArgs << '.'

        dir(buildDir) {
          sh "docker buildx build ${buildArgs.join(' ')}"
        }

        if (!noPush) {
          def meta = readJSON(file: metadataFile)
          def digest = meta['containerimage.digest']
          dockerdigest.add("${dockerRepo}:${dockerTag}@${digest}")
        }
      } // stage('build and push')
    } // run

    util.nodeWrap(lsstswConfig.label) {
      timeout(time: 4, unit: 'HOURS') {
        run()
      }
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
        def digest = dockerdigest.join(' ')
        registryTags.each { name ->
          sh "docker buildx imagetools create -t ${dockerRepo}:${name} ${digest}"
        }
      }
    }
  } // merge
  if (!noPush && !dockerdigest.isEmpty()) {
    util.nodeWrap('linux-64') {
      timeout(time: 1, unit: 'HOURS') {
        merge()
      }
    } // nodeWrap
  }

} // notify.wrap
