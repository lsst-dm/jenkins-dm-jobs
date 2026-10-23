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
    'NO_CACHE',
  ])

  Boolean noPush  = params.NO_PUSH
  Boolean noCache = params.NO_CACHE

  // Build on the same platforms newinstall does, so the manifest list covers
  // every arch that will FROM this image.
  def lsstswConfigs = scipipe.build_stack.lsstsw_config
  def dockerfile    = sqre.scipipe_base.dockerfile
  def githubRepo    = util.githubSlugToUrl(dockerfile.github_repo)
  def gitRef        = dockerfile.git_ref
  def buildDir      = dockerfile.dir
  def dockerRepo    = sqre.scipipe_base.docker_registry.repo
  def dockerTag     = sqre.scipipe_base.docker_registry.tag
  def cacheRepo     = util.buildcacheRepo('scipipe-base')
  // display_name suffix -> OCI arch, so the per-arch tags carry the names
  // buildx itself would use.
  def archByName    = [x86: 'amd64', aarch64: 'arm64']
  def dockerdigest  = []

  def matrix = [:]
  lsstswConfigs.each { lsstswConfig ->
    def slug = util.lsstswConfigSlug(lsstswConfig)
    def arch = archByName[lsstswConfig.display_name.tokenize('-').last()]
    if (!arch) {
      error "no OCI arch mapping for ${lsstswConfig.display_name}"
    }
    def archTag = "${dockerRepo}:${dockerTag}_${arch}"

    matrix[slug] = {
      util.nodeWrap(lsstswConfig.label) {
        timeout(time: 1, unit: 'HOURS') {
          stage("checkout ${arch}") {
            git([
              url: githubRepo,
              branch: gitRef,
            ])
          }

          stage("build ${arch}") {
            def metadataFile = '/tmp/build-metadata.json'

            util.setupBuildkitBuilder()

            // --cache-from needs registry auth even on NO_PUSH runs.
            withCredentials([usernamePassword(
              credentialsId: 'google_archive_registry_sa',
              usernameVariable: 'AR_USER',
              passwordVariable: 'AR_TOKEN',
            )]) {
              sh 'echo $AR_TOKEN | docker login us-central1-docker.pkg.dev -u $AR_USER --password-stdin'
            }

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
              util.buildkitCacheArgs(cacheRepo, arch, !noPush),
              "--metadata-file ${metadataFile}",
            ]
            if (noCache) {
              buildArgs << '--no-cache'
            }
            if (!noPush) {
              buildArgs << '--push'
              buildArgs << "--tag ${archTag}"
            }
            buildArgs << '.'

            dir(buildDir) {
              sh "docker buildx build ${buildArgs.join(' ')}"
            }

            if (!noPush) {
              def meta = readJSON(file: metadataFile)
              dockerdigest.add("${archTag}@${meta['containerimage.digest']}")
            }
          } // stage build
        } // timeout
      } // nodeWrap
    }
  }
  parallel matrix

  // Both arches pushed under suffixed tags; publish the real tag as a
  // manifest list pointing at both digests.
  if (!noPush && !dockerdigest.isEmpty()) {
    util.nodeWrap('linux-64') {
      timeout(time: 30, unit: 'MINUTES') {
        stage('manifest') {
          withCredentials([usernamePassword(
            credentialsId: 'rubinobs-dm',
            usernameVariable: 'GHCR_USER',
            passwordVariable: 'GHCR_TOKEN',
          )]) {
            sh 'echo $GHCR_TOKEN | docker login ghcr.io -u $GHCR_USER --password-stdin'
          }
          sh "docker buildx imagetools create -t ${dockerRepo}:${dockerTag} ${dockerdigest.join(' ')}"
        }
      }
    }
  }
} // notify.wrap
