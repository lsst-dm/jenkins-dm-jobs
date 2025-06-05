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
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams([
    'SOURCE_GIT_REFS',
    'RELEASE_GIT_TAG',
    'SPLENV_REF',
    'RUBINENV_VER',
    'O_LATEST',
    'EXCLUDE_FROM_BUILDS',
    'EXCLUDE_FROM_TARBALLS',
  ])

  String sourceGitRefs    = params.SOURCE_GIT_REFS
  String gitTag           = params.RELEASE_GIT_TAG
  String splenvRef        = params.SPLENV_REF
  String rubinEnvVer      = params.RUBINENV_VER
  Boolean dockerLatest    = params.O_LATEST
  String exclude_builds   = params.EXCLUDE_FROM_BUILDS
  String exclude_tarballs = params.EXCLUDE_FROM_TARBALLS

  // define source git refs for tagging auxilliaries
  def refs = params.SOURCE_GIT_REFS
  def refArgs = refs?.trim() ? refs.split().collectMany { ["-r", it] } : []

  // generate eups tag from git tag
  String eupsTag = util.sanitizeEupsTag(gitTag)

  // determine if this is an rc release (does not start with an integer)
  Boolean finalRelease = !!(gitTag =~ /^\d/)

  // o_latest is only allowed for a final release
  if (dockerLatest && !finalRelease) {
    error "O_LATEST is only allowed on final (non-rc) releases"
  }

  // type of eupspkg to create -- "git" should always be used except for a
  // final (non-rc) release
  String eupspkgSource = finalRelease ? 'package' : 'git'

  // A final release git tag for a "regular" package does not start with a `v`.
  // However, the release git tag for an external packages is always prefixed
  // with a `v`.  Thus, in the case of a final release, we need to use both
  // v<tag> and <tag> as the source git refs to produce the eups source
  // packages.
  def buildGitTags = finalRelease ? "v${gitTag} ${gitTag}" : gitTag

  def lsstswConfig = scipipe.canonical.lsstsw_config

  echo "final release (non-rc): ${finalRelease}"
  echo "EUPSPKG_SOURCE: ${eupspkgSource}"
  echo "input git refs: ${sourceGitRefs}"
  echo "build git refs: ${buildGitTags}"
  echo "publish [git] tag: ${gitTag}"
  echo "publish [eups] tag: ${eupsTag}"

  def prodTarballList = scipipe.tarball.products.split(' ')
  def excludeTarballs = exclude_tarballs.split(' ')
  def tarballProducts = ''

  // remove excluded products from tarball build list
  for(prod in prodTarballList)
    if(! (prod in excludeTarballs)) tarballProducts += prod + ' '
  tarballProducts = tarballProducts.trim()

  // remove excludes products from build list
  def excludeBuilds = exclude_builds.split(' ')
  def prodList = scipipe.canonical.products.split(' ')
  def products = ''

  for(prod in prodList)
    if(! (prod in excludeBuilds)) products += prod + ' '
  products = products.trim()

  def retries         = 3
  def extraDockerTags = ''

  if (dockerLatest) {
    extraDockerTags = 'o_latest'
  }

  def gitTagOnlyManifestId = null
  def manifestId           = null
  def stackResults         = null

  def run = {
    stage('generate manifest') {
      retry(retries) {
          gitTagOnlyManifestId = util.runRebuild(
          parameters: [
            PRODUCTS: products,
            REFS: sourceGitRefs,
            SPLENV_REF: splenvRef,
            BUILD_DOCS: false,
            PREP_ONLY: true,
          ],
        )
      } // retry
    } // stage

    stage('git tag eups products') {
      retry(retries) {
        util.nodeWrap('linux-64') {
          util.githubTagRelease(
            options: [
              '--dry-run': false,
              '--org': scipipe.release_tag_org,
              '--manifest': gitTagOnlyManifestId,
              '--manifest-only': true,
              '--ignore-git-message': true,
              '--ignore-git-tagger': true,
            ],
            args: [gitTag],
          )
        } // util.nodeWrap
      } // retry
    } // stage

    // add aux repo tags *after* tagging eups product repos so as to avoid a
    // trainwreck if an aux repo has been pulled into the build (without
    // first being removed from the aux team).
    stage('git tag auxilliaries') {
      retry(retries) {
        util.nodeWrap('linux-64') {
          util.githubTagTeams(
            options: [
              '--dry-run': false,
              '--org': scipipe.release_tag_org,
              '--tag': gitTag,
            ],
            args: refArgs,
          )
        } // util.nodeWrap
      } // retry
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            REFS: buildGitTags,
            PRODUCTS: products,
            SPLENV_REF: splenvRef,
            BUILD_DOCS: true,
            NO_BINARY_FETCH: true,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      def pub = [:]

      [eupsTag, 'o_latest'].each { tagName ->
        pub[tagName] = {
          retry(retries) {
            util.runPublish(
              parameters: [
                EUPSPKG_SOURCE: eupspkgSource,
                MANIFEST_ID: manifestId,
                EUPS_TAG: tagName,
                PRODUCTS: products,
                SPLENV_REF: splenvRef,
                RUBINENV_VER: rubinEnvVer,
              ],
            )
          } // retry
        } // pub
      } // each

      parallel pub
    } // stage

    util.waitForS3()

    stage('build eups tarballs') {
      util.buildTarballMatrix(
        tarballConfigs: scipipe.tarball.build_config,
        parameters: [
          PRODUCTS: tarballProducts,
          EUPS_TAG: eupsTag,
          SPLENV_REF: splenvRef,
          RUBINENV_VER: rubinEnvVer,
          SMOKE: true,
          RUN_SCONS_CHECK: true,
          PUBLISH: true,
        ],
        retries: retries,
      )
    } // stage

    util.waitForS3()

    stage('build stack image') {
      retry(retries) {
        stackResults = util.runBuildStack(
          parameters: [
            PRODUCTS: tarballProducts,
            EUPS_TAG: eupsTag,
            DOCKER_TAGS: extraDockerTags,
            MANIFEST_ID: manifestId,
            LSST_COMPILER: lsstswConfig.compiler,
            SPLENV_REF: rubinEnvVer,
            // This is not splenvRef, which could be an eups tag.
            // This parameter is used to select the base newinstall container,
            // which only has rubin-env version tags.
          ],
        )
      } // retry
    } // stage

    def triggerMe = [:]

    triggerMe['build Science Platform Notebook Aspect Lab image'] = {
      retry(retries) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/infra/build-sciplatlab',
          parameters: [
            string(name: 'TAG', value: eupsTag),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['verify_drp_metrics x86'] = {
      retry(1) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/verify_drp_metrics',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            string(name: 'ARCHITECTURE', value: 'linux-64'),
            string(name: 'DATASET_REF', value: "refs/tags/" + gitTag),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.verify_drp_metrics.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['verify_drp_metrics aarch64'] = {
      retry(1) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/verify_drp_metrics',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            string(name: 'ARCHITECTURE', value: 'linux-aarch64'),
            string(name: 'DATASET_REF', value: "refs/tags/" + gitTag),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.verify_drp_metrics.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['doc build'] = {
      retry(retries) {
        build(
          job: 'sqre/infra/documenteer',
          parameters: [
            string(name: 'EUPS_TAG', value: eupsTag),
            string(name: 'LTD_SLUG', value: eupsTag),
            string(name: 'RELEASE_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'PUBLISH',
              value: scipipe.release.step.documenteer.publish,
            ),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['ap_verify x86'] = {
      retry(retries) {
        build(
          job: 'scipipe/ap_verify',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            string(name: 'ARCHITECTURE', value: 'linux-64'),
            string(name: 'DATASET_REF', value: "refs/tags/" + gitTag),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.ap_verify.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['ap_verify aarch64'] = {
      retry(retries) {
        build(
          job: 'scipipe/ap_verify',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            string(name: 'ARCHITECTURE', value: 'linux-aarch64'),
            string(name: 'DATASET_REF', value: "refs/tags/" + gitTag),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.ap_verify.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    stage('triggered jobs') {
      parallel triggerMe
    } // stage
  } // run

  try {
    timeout(time: 30, unit: 'HOURS') {
      run()
    }
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        util.dumpJson(resultsFile, [
          build_git_tags:   buildGitTags ?: null,
          eups_tag:         eupsTag ?: null,
          final_release:    finalRelease ?: null,
          git_tag:          gitTag ?: null,
          manifest_id:      manifestId ?: null,
          products:         products ?: null,
          tarball_products: tarballProducts ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
