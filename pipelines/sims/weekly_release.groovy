node {
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
    sims = util.simsConfig()
  }
}

notify.wrap {
  util.requireParams([
    'YEAR',
    'WEEK',
    'LSST_DISTRIB_GIT_TAG',
  ])

  String year              = params.YEAR.padLeft(4, "0")
  String week              = params.WEEK.padLeft(2, "0")
  String lsstDistribGitTag = params.LSST_DISTRIB_GIT_TAG

  def products        = sims.canonical.products
  def tarballProducts = sims.tarball.products
  def retries         = 3

  def eupsTag            = null
  def manifestId         = null
  def lsstDistribWeekly  = null

  def run = {
    stage('format weekly tag') {
      eupsTag = util.sanitizeEupsTag("sims_w_${year}_${week}")
      echo "generated [eups] tag: ${eupsTag}"
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            REFS: lsstDistribGitTag,
            PRODUCTS: products,
            BUILD_DOCS: false,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      def pub = [:]

      [eupsTag].each { tagName ->
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
          eups_tag: eupsTag ?: null,
          lsst_distrib_git_tag: lsstDistribGitTag ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
