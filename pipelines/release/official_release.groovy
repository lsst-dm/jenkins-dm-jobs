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
    config = util.readYamlFile 'etc/science_pipelines/build_matrix.yaml'
    sqre = util.readYamlFile 'etc/sqre/config.yaml'
  }
}

notify.wrap {
  util.requireParams([
    'EUPSPKG_SOURCE',
    'EUPS_TAG',
    'GIT_TAG',
    'O_LATEST',
    'SOURCE_EUPS_TAG',
    'SOURCE_MANIFEST_ID',
  ])

  String eupspkgSource = params.EUPSPKG_SOURCE
  String eupsTag       = params.EUPS_TAG
  String gitTag        = params.GIT_TAG
  Boolean oLatest      = params.O_LATEST
  String srcEupsTag    = params.SOURCE_EUPS_TAG
  String srcManifestId = params.SOURCE_MANIFEST_ID

  echo "EUPSPKG_SOURCE: ${eupspkgSource}"
  echo "publish [eups] tag: ${eupsTag}"
  echo "publish [git] tag: ${gitTag}"
  echo "source [eups] tag: ${srcEupsTag}"
  echo "source manifest id: ${srcManifestId}"

  def product         = 'lsst_distrib'
  def tarballProducts = product
  def retries         = 3
  def buildJob        = 'release/run-rebuild'
  def publishJob      = 'release/run-publish'

  def manifestId = null

  try {
    timeout(time: 30, unit: 'HOURS') {

      stage('git tag eups products') {
        retry(retries) {
          node('docker') {
            // needs eups distrib tag to be sync'd from s3 -> k8s volume
            util.githubTagRelease(
              gitTag,
              srcEupsTag,
              srcManifestId,
              [
                '--dry-run': false,
                '--org': config.release_tag_org,
              ]
            )
          } // node
        } // retry
      }

      // add aux repo tags *after* tagging eups product repos so as to avoid a
      // trainwreck if an aux repo has been pulled into the build (without
      // first being removed from the aux team).
      stage('git tag auxilliaries') {
        retry(retries) {
          node('docker') {
            util.githubTagTeams(
              [
                '--dry-run': false,
                '--org': config.release_tag_org,
                '--tag': gitTag,
              ]
            )
          } // node
        } // retry
      }

      stage('build') {
        retry(retries) {
          manifestId = util.runRebuild(buildJob, [
            BRANCH: gitTag,
            PRODUCT: product,
            SKIP_DEMO: false,
            SKIP_DOCS: false,
            TIMEOUT: '8', // hours
          ])
        }
      }

      stage('eups publish') {
        def pub = [:]

        pub[eupsTag] = {
          retry(retries) {
            util.runPublish(manifestId,
              eupsTag, product, eupspkgSource, publishJob)
          }
        }
        if (oLatest) {
          pub['o_latest'] = {
            retry(retries) {
              util.runPublish(manifestId,
                'o_latest', product, eupspkgSource, publishJob)
            }
          }
        }

        parallel pub
      }

      stage('wait for s3 sync') {
        sleep time: 15, unit: 'MINUTES'
      }

      stage('build eups tarballs') {
       def opt = [
          SMOKE: true,
          RUN_DEMO: true,
          RUN_SCONS_CHECK: true,
          PUBLISH: true,
        ]

        util.buildTarballMatrix(config, tarballProducts, eupsTag, opt)
      }

      stage('wait for s3 sync') {
        sleep time: 15, unit: 'MINUTES'
      }

      stage('build stack image') {
        retry(retries) {
          build job: 'release/docker/build-stack',
            parameters: [
              string(name: 'PRODUCT', value: tarballProducts),
              string(name: 'TAG', value: eupsTag),
            ]
        }
      }

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
            ],
            wait: false
        }
      }

      stage('validate_drp') {
        // XXX use the same compiler as is configured for the canonical build
        // env.  This is a bit of a kludge.  It would be better to directly
        // label the compiler used on the dockage image.
        def can = config.canonical

        retry(1) {
          // based on lsstsqre/stack image
          build job: 'sqre/validate_drp',
            parameters: [
              string(name: 'EUPS_TAG', value: eupsTag),
              string(name: 'MANIFEST_ID', value: manifestId),
              string(name: 'COMPILER', value: can.compiler),
              booleanParam(
                name: 'NO_PUSH',
                value: sqre.validate_drp.no_push,
              ),
              booleanParam(name: 'WIPEOUT', value: true),
            ],
            wait: false
        }
      }

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
        }
      }
    } // timeout
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        results = [:]
        if (manifestId) {
          results['manifest_id'] = manifestId
        }
        if (gitTag) {
          results['git_tag'] = gitTag
        }
        if (eupsTag) {
          results['eups_tag'] = eupsTag
        }

        util.dumpJson(resultsFile, results)

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    }
  } // try
} // notify.wrap
