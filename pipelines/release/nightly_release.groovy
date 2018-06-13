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
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams(['YEAR', 'MONTH', 'DAY'])

  String year  = params.YEAR.padLeft(4, "0")
  String month = params.MONTH.padLeft(2, "0")
  String day   = params.DAY.padLeft(2, "0")

  def product         = 'lsst_distrib'
  def tarballProducts = product
  def retries         = 3
  def buildJob        = 'release/run-rebuild'
  def publishJob      = 'release/run-publish'

  def gitTag       = null
  def eupsTag      = null
  def manifestId   = null
  def stackResults = null

  def run = {
    stage('format nightly tag') {
      gitTag  = "d.${year}.${month}.${day}"
      eupsTag = util.sanitizeEupsTag(gitTag)
      echo "generated [git] tag: ${gitTag}"
      echo "generated [eups] tag: ${eupsTag}"
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(buildJob, [
          PRODUCT: product,
          SKIP_DEMO: false,
          SKIP_DOCS: false,
          TIMEOUT: '8', // hours
        ])
      } // retry
    } // stage

    stage('eups publish') {
      def pub = [:]

      pub[eupsTag] = {
        retry(retries) {
          util.runPublish(manifestId, eupsTag, product, 'git', publishJob)
        }
      }
      pub['d_latest'] = {
        retry(retries) {
          util.runPublish(manifestId, 'd_latest', product, 'git', publishJob)
        }
      }

      parallel pub
    } // stage

    stage('wait for s3 sync') {
      sleep time: 15, unit: 'MINUTES'
    }

    // NOOP / DRY_RUN
    stage('git tag eups products') {
      retry(retries) {
        node('docker') {
          // needs eups distrib tag to be sync'd from s3 -> k8s volume
          util.githubTagRelease(
            options: [
              '--dry-run': true,
              '--org': config.release_tag_org,
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
              '--org': config.release_tag_org,
              '--tag': gitTag,
            ],
          )
        } // node
      } // retry
    } // stage

    stage('build eups tarballs') {
     def opt = [
        SMOKE: true,
        RUN_DEMO: true,
        RUN_SCONS_CHECK: true,
        PUBLISH: true,
      ]

      util.buildTarballMatrix(config, tarballProducts, eupsTag, opt)
    } // stage

    stage('wait for s3 sync') {
      sleep time: 15, unit: 'MINUTES'
    }

    stage('build stack image') {
      retry(retries) {
        stackResults = util.runBuildStack(
          job: 'release/docker/build-stack',
          parameters: [
            'PRODUCT': tarballProducts,
            'TAG': eupsTag,
          ],
        )
      } // retry
    } // stage

    stage('build jupyterlabdemo image') {
      retry(retries) {
        // based on lsstsqre/stack image
        build job: 'sqre/infrastructure/build-jupyterlabdemo',
          parameters: [
            string(name: 'TAG', value: eupsTag),
            booleanParam(name: 'NO_PUSH', value: false),
            string(
              name: 'IMAGE_NAME',
              value: sqre.build_jupyterlabdemo.image_name,
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
            booleanParam(
              name: 'NO_PUSH',
              value: sqre.validate_drp.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: true),
          ],
          wait: false
      } // retry
    } // stage

    stage('doc build') {
      retry(retries) {
        build job: 'sqre/infrastructure/documenteer',
          parameters: [
            string(name: 'EUPS_TAG', value: eupsTag),
            string(name: 'LTD_SLUG', value: eupsTag),
            booleanParam(
              name: 'PUBLISH',
              value: sqre.documenteer.publish,
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
