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
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams(['YEAR', 'MONTH', 'DAY'])

  String year  = params.YEAR.padLeft(4, "0")
  String month = params.MONTH.padLeft(2, "0")
  String day   = params.DAY.padLeft(2, "0")

  def products        = scipipe.canonical.products
  def tarballProducts = scipipe.tarball.products
  def retries         = 3
  def extraDockerTags = '7-stack-lsst_distrib-d_latest d_latest'

  def gitTag       = null
  def eupsTag      = null
  def manifestId   = null
  def stackResults = null

  def lsstswConfig = scipipe.canonical.lsstsw_config

  def run = {
    stage('format nightly tag') {
      gitTag  = "d.${year}.${month}.${day}"
      eupsTag = util.sanitizeEupsTag(gitTag)
      echo "generated [git] tag: ${gitTag}"
      echo "generated [eups] tag: ${eupsTag}"
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            PRODUCTS: products,
            BUILD_DOCS: true,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      def pub = [:]

      [eupsTag, 'd_latest'].each { tagName ->
        pub[tagName] = {
          retry(retries) {
            util.runPublish(
              parameters: [
                EUPSPKG_SOURCE: 'git',
                MANIFEST_ID: manifestId,
                EUPS_TAG: tagName,
                PRODUCTS: products,
              ],
            )
          } // retry
        } // pub
      } // each

      parallel pub
    } // stage

    util.waitForS3()

    // NOOP / DRY_RUN
    stage('git tag eups products') {
      retry(retries) {
        node('docker') {
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
        } // node
      } // retry
    } // stage

    // add aux repo tags *after* tagging eups product repos so as to avoid a
    // trainwreck if an aux repo has been pulled into the build (without
    // first being removed from the aux team).
    stage('git tag auxilliaries') {
      retry(retries) {
        node('docker') {
          util.githubTagTeams(
            options: [
              '--dry-run': true,
              '--org': scipipe.release_tag_org,
              '--tag': gitTag,
            ],
          )
        } // node
      } // retry
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
          ],
        )
      } // retry
    } // stage

    def triggerMe = [:]

    triggerMe['build jupyterlabdemo image'] = {
      retry(retries) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/infra/build-jupyterlabdemo',
          parameters: [
            string(name: 'TAG', value: eupsTag),
            booleanParam(name: 'NO_PUSH', value: false),
            string(
              name: 'IMAGE_NAME',
              value: scipipe.release.step.build_jupyterlabdemo.image_name,
            ),
            // BASE_IMAGE is the registry repo name *only* without a tag
            string(
              name: 'BASE_IMAGE',
              value: stackResults.docker_registry.repo,
            ),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['validate_drp'] = {
      // XXX use the same compiler as is configured for the canonical build
      // env.  This is a bit of a kludge.  It would be better to directly
      // label the compiler used on the docker image.

      retry(1) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/validate_drp',
          parameters: [
            string(name: 'COMPILER', value: lsstswConfig.compiler),
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            string(name: 'MANIFEST_ID', value: manifestId),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.validate_drp.no_push,
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

    triggerMe['ap_verify'] = {
      retry(retries) {
        build(
          job: 'scipipe/ap_verify',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
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
