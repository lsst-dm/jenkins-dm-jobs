node('jenkins-manager') {
  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams(['YEAR', 'MONTH', 'DAY'])

  String year  = params.YEAR.padLeft(4, "0")
  String month = params.MONTH.padLeft(2, "0")
  String day   = params.DAY.padLeft(2, "0")
  if (year == "0000"){
      def now = new Date()
      year = now.format("YYYY")
      month = now.format("MM")
      day = now.format("dd")
  }

  def products        = scipipe.canonical.products
  def tarballProducts = scipipe.tarball.products
  def retries         = 3
  def extraDockerTags = 'd_latest'

  def gitTag       = null
  def eupsTag      = null
  def manifestId   = null
  def stackResults = null
  def cacheOk      = false

  def lsstswConfig = scipipe.canonical.lsstsw_config

  def rebuildBranch = {
    stage('build') {
      retry(retries) {
        // publish runs inside the rebuild pod (same stack), tagging both the
        // dated eups tag and d_latest.
        manifestId = util.runRebuild(
          parameters: [
            PRODUCTS: products,
            BUILD_DOCS: true,
            NO_BINARY_FETCH: true,
            PUBLISH: true,
            EUPS_TAG: "${eupsTag} d_latest",
            EUPSPKG_SOURCE: 'git',
          ],
        )
      } // retry
    } // stage
  } // rebuildBranch

  // The lsstsw cache cannot be produced by release/run-rebuild: an lsstsw tree is
  // not relocatable -- conda bakes its prefix into
  // miniconda/etc/profile.d/conda.sh and every miniconda/bin shebang -- and
  // run-rebuild builds at /j/snowflake/release while every cache consumer
  // extracts to /j/workspace/stack-os-matrix/<slug>. Only a stack-os-matrix build
  // lands at that path, so the cache is built by one here.
  def cacheBranch = {
    stage('build lsstsw cache') {
      try {
        retry(retries) {
          build(
            job: 'stack-os-matrix',
            parameters: [
              string(name: 'REFS', value: ''),
              string(name: 'PRODUCTS', value: products),
              string(name: 'SPLENV_REF', value: scipipe.template.splenv_ref),
              booleanParam(name: 'NO_BINARY_FETCH', value: true),
              booleanParam(name: 'LOAD_CACHE', value: false),
              booleanParam(name: 'SAVE_CACHE', value: true),
              // temporary tag; promoted to d_latest only once the release has
              // succeeded, so a failed release cannot advance the shared cache
              string(name: 'SAVE_CACHE_TAG', value: eupsTag),
            ],
            wait: true,
          )
        } // retry
        cacheOk = true
      } catch (e) {
        // nothing the release publishes depends on the cache, so a cache
        // failure must not fail an otherwise good release -- d_latest simply
        // keeps pointing at the last stack that built cleanly.
        currentBuild.result = 'UNSTABLE'
        echo "lsstsw cache build failed, leaving d_latest untouched: ${e}"
      }
    } // stage
  } // cacheBranch

  def run = {
    stage('format nightly tag') {
      gitTag  = "d.${year}.${month}.${day}"
      eupsTag = util.sanitizeEupsTag(gitTag)
      echo "generated [git] tag: ${gitTag}"
      echo "generated [eups] tag: ${eupsTag}"
    } // stage

    // Both branches build the same products from the same refs, so they run
    // concurrently rather than one after the other. failFast: false so a cache
    // failure cannot abort the rebuild.
    parallel([
      'rebuild':      rebuildBranch,
      'lsstsw cache': cacheBranch,
      failFast:       false,
    ])

    // NOOP / DRY_RUN
    stage('git tag eups products') {
      retry(retries) {
        util.nodeWrap('linux-64') {
          // needs eups distrib tag to be sync'd from s3 -> k8s volume
          util.githubTagRelease(
            options: [
              '--dry-run': true,
              '--org': scipipe.release_tag_org,
              '--manifest': manifestId,
              '--eups-tag': eupsTag,
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
              '--dry-run': true,
              '--org': scipipe.release_tag_org,
              '--tag': gitTag,
            ],
          )
        } // util.nodeWrap
      } // retry
    } // stage
    stage('update index files'){
      util.runIndexUpdate()
    } // stage

    stage('build eups tarballs') {
      util.buildTarballMatrix(
        tarballConfigs: scipipe.tarball.build_config,
        parameters: [
          PRODUCTS: tarballProducts,
          EUPS_TAG: eupsTag,
          SMOKE: true,
          RUN_SCONS_CHECK: true,
          PUBLISH: true,
        ],
        retries: retries,
      )
    } // stage
    stage('update index files'){
      util.runIndexUpdate()
    } // stage

    stage('build stack image') {
      retry(retries) {
        stackResults = util.runBuildStack(
          parameters: [
            PRODUCTS: tarballProducts,
            EUPS_TAG: eupsTag,
            DOCKER_TAGS: extraDockerTags,
            MANIFEST_ID: manifestId,
            LSST_COMPILER: lsstswConfig.compiler,
          ],
        )
      } // retry
    } // stage

    def triggerMe = [:]

    triggerMe['Update cache + sonar-scan'] = {
      // Promoting here rather than in the cache branch means d_latest is never
      // advanced by a release that failed after the build.
      if (!cacheOk) {
        echo 'lsstsw cache build did not succeed; skipping promotion and sonar-scan'
        return
      }
      retry(retries) {
        util.promoteCache(eupsTag, 'd_latest')
      }
      build(
        job: 'sqre/infra/sonar-scan',
        parameters: [
          string(name: 'EUPS_TAG',  value: eupsTag),
          string(name: 'CACHE_TAG', value: 'd_latest'),
        ],
        wait: false,
      )
    }
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
    ['linux-64','linux-aarch64'].each{ arch ->
      triggerMe['verify_drp_metrics ' + arch] = {
        retry(1) {
          // based on lsstsqre/stack image
          build(
            job: 'sqre/verify_drp_metrics',
            parameters: [
              string(name: 'DOCKER_IMAGE', value: stackResults.image),
              string(name: 'ARCHITECTURE', value: arch),
              booleanParam(
                name: 'NO_PUSH',
                value: scipipe.release.step.verify_drp_metrics.no_push,
              ),
              booleanParam(name: 'WIPEOUT', value: false),
              string(name: 'GIT_REF', value: 'main'),
            ],
            wait: false,
          )
        } // retry
      } // verify_drp_metrics
      triggerMe['ap_verify ' + arch] = {
        retry(retries) {
          build(
            job: 'scipipe/ap_verify',
            parameters: [
              string(name: 'DOCKER_IMAGE', value: stackResults.image),
              string(name: 'ARCHITECTURE', value: arch),
              booleanParam(
                name: 'NO_PUSH',
                value: scipipe.release.step.ap_verify.no_push,
              ),
              booleanParam(name: 'WIPEOUT', value: false),
            ],
            wait: false,
          )
        } // retry
      } // ap_verify
    } //each

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
          manifest_id: manifestId ?: null,
          git_tag: gitTag ?: null,
          eups_tag: eupsTag ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
