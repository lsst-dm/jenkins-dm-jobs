def config = null

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
    config = util.scipipeConfig()
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams([
    'EUPSPKG_SOURCE',
    'EUPS_TAG',
    'GIT_TAG',
    'MANIFEST_ONLY',
    'O_LATEST',
    'SOURCE_EUPS_TAG',
    'SOURCE_MANIFEST_ID',
  ])

  String eupspkgSource = params.EUPSPKG_SOURCE
  String eupsTag       = params.EUPS_TAG
  String gitTag        = params.GIT_TAG
  Boolean manifestOnly = params.MANIFEST_ONLY
  Boolean oLatest      = params.O_LATEST
  // '' means null
  String srcEupsTag    = util.emptyToNull(params.SOURCE_EUPS_TAG)
  String srcManifestId = params.SOURCE_MANIFEST_ID

  echo "EUPSPKG_SOURCE: ${eupspkgSource}"
  echo "publish [eups] tag: ${eupsTag}"
  echo "publish [git] tag: ${gitTag}"
  echo "source [eups] tag: ${srcEupsTag}"
  echo "source manifest id: ${srcManifestId}"

  def product         = 'lsst_distrib'
  def tarballProducts = product
  def retries         = 3

  def manifestId   = null
  def stackResults = null

  def run = {
    stage('git tag eups products') {
      retry(retries) {
        node('docker') {
          // needs eups distrib tag to be sync'd from s3 -> k8s volume
          util.githubTagRelease(
            options: [
              '--dry-run': false,
              '--org': config.release_tag_org,
              '--manifest': srcManifestId,
              '--eups-tag': srcEupsTag, // ommited if null
              '--manifest-only': manifestOnly,
              '--ignore-git-message': true,
              '--ignore-git-tagger': true,
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
              '--dry-run': false,
              '--org': config.release_tag_org,
              '--tag': gitTag,
            ],
          )
        } // node
      } // retry
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            BRANCH: gitTag,
            PRODUCT: product,
            SKIP_DEMO: false,
            SKIP_DOCS: false,
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
                PRODUCT: product,
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
        tarballConfigs: config.tarball,
        parameters: [
          PRODUCT: tarballProducts,
          EUPS_TAG: eupsTag,
          SMOKE: true,
          RUN_DEMO: true,
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
            PRODUCT: tarballProducts,
            TAG: eupsTag,
          ],
        )
      } // retry
    } // stage

    stage('build jupyterlabdemo image') {
      retry(retries) {
        // based on lsstsqre/stack image
        build job: 'sqre/infra/build-jupyterlabdemo',
          parameters: [
            string(name: 'TAG', value: eupsTag),
            booleanParam(name: 'NO_PUSH', value: false),
            string(
              name: 'IMAGE_NAME',
              value: config.release.step.build_jupyterlabdemo.image_name,
            ),
            // BASE_IMAGE is the registry repo name *only* without a tag
            string(
              name: 'BASE_IMAGE',
              value: stackResults.docker_registry.repo,
            ),
          ],
          wait: false
      } // retry
    } // stage

    stage('validate_drp') {
      // XXX use the same compiler as is configured for the canonical build
      // env.  This is a bit of a kludge.  It would be better to directly
      // label the compiler used on the dockage image.
      def lsstswConfig = config.canonical.lsstsw_config

      retry(1) {
        // based on lsstsqre/stack image
        build job: 'sqre/validate_drp',
          parameters: [
            string(name: 'EUPS_TAG', value: eupsTag),
            string(name: 'MANIFEST_ID', value: manifestId),
            string(name: 'COMPILER', value: lsstswConfig.compiler),
            string(name: 'RELEASE_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'NO_PUSH',
              value: config.release.step.validate_drp.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: true),
          ],
          wait: false
      } // retry
    } // stage

    stage('doc build') {
      retry(retries) {
        build job: 'sqre/infra/documenteer',
          parameters: [
            string(name: 'EUPS_TAG', value: eupsTag),
            string(name: 'LTD_SLUG', value: eupsTag),
            string(name: 'RELEASE_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'PUBLISH',
              value: config.release.step.documenteer.publish,
            ),
          ]
      } // retry
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
