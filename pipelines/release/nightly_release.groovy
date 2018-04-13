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
  }
}

notify.wrap {
  def gitTag = null
  def eupsTag = null
  def bx = null

  try {
    timeout(time: 30, unit: 'HOURS') {
      def product         = 'lsst_distrib qserv_distrib'
      def tarballProducts = 'lsst_distrib'

      def retries = 3
      def rebuildId = null
      def buildJob = 'release/run-rebuild'
      def publishJob = 'release/run-publish'

      def year = null
      def month = null
      def day = null

      stage('format nightly tag') {
        if (!params.YEAR) {
          error 'YEAR parameter is required'
        }
        if (!params.MONTH) {
          error 'MONTH parameter is required'
        }
        if (!params.DAY) {
          error 'DAY parameter is required'
        }

        year = params.YEAR.padLeft(4, "0")
        month = params.MONTH.padLeft(2, "0")
        day = params.DAY.padLeft(2, "0")

        gitTag = "d.${year}.${month}.${day}"
        echo "generated [git] tag: ${gitTag}"

        // eups doesn't like dots in tags, convert to underscores
        eupsTag = gitTag.tr('.', '_')
        echo "generated [eups] tag: ${eupsTag}"
      }

      stage('build') {
        retry(retries) {
          def result = build job: buildJob,
            parameters: [
              string(name: 'PRODUCT', value: product),
              booleanParam(name: 'SKIP_DEMO', value: false),
              booleanParam(name: 'SKIP_DOCS', value: false),
              string(name: 'TIMEOUT', value: '8'), // hours

            ],
            wait: true
          rebuildId = result.id
        }
      }

      stage('parse bNNNN') {
        util.nodeTiny {
          manifest_artifact = 'lsstsw/build/manifest.txt'

          step([$class: 'CopyArtifact',
                projectName: buildJob,
                filter: manifest_artifact,
                selector: [
                  $class: 'SpecificBuildSelector',
                  buildNumber: rebuildId
                ],
              ])

          def manifest = readFile manifest_artifact
          bx = util.bxxxx(manifest)

          echo "parsed bxxxx: ${bx}"
        }
      }

      stage('eups publish') {
        def pub = [:]

        pub[eupsTag] = {
          retry(retries) {
            util.tagProduct(bx, eupsTag, product, publishJob)
          }
        }
        pub['d_latest'] = {
          retry(retries) {
            util.tagProduct(bx, 'd_latest', product, publishJob)
          }
        }

        parallel pub
      }

      stage('wait for s3 sync') {
        sleep time: 15, unit: 'MINUTES'
      }

      // NOOP / DRY_RUN
      stage('git tag') {
        retry(retries) {
          node('docker') {
            // needs eups distrib tag to be sync'd from s3 -> k8s volume
            util.githubTagVersion(
              gitTag,
              bx,
              [
                '--dry-run': true,
                '--team': ['Data Management', 'DM Externals'],
              ]
            )
          } // node
        } // retry
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
            ],
            wait: false
        }
      }

      stage('validate_drp') {
        // XXX use the same compiler as is configured for the canoncial build
        // env.  This is a bit of a kludge.  It would be better to directly
        // label the compiler used on the dockage image.
        def can = config.canonical

        retry(1) {
          // based on lsstsqre/stack image
          build job: 'sqre/validate_drp',
            parameters: [
              string(name: 'EUPS_TAG', value: eupsTag),
              string(name: 'BNNNN', value: bx),
              string(name: 'COMPILER', value: can.compiler),
              booleanParam(name: 'NO_PUSH', value: false),
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
            ]
        }
      }
    } // timeout
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        results = [:]
        if (bx) {
          results['bnnn'] = bx
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

/**
 * Represents a miniconda build environment.
 */
class MinicondaEnv implements Serializable {
  String pythonVersion
  String minicondaVersion
  String lsstswRef

  /**
   * Constructor.
   *
   * @param p Python major version number. Eg., '3'
   * @param m Miniconda version string. Eg., '4.2.12'
   * @param l {@code lsst/lsstsw} git ref.
   * @return MinicondaEnv
   */
  // unfortunately, a constructor is required under the security sandbox
  // See: https://issues.jenkins-ci.org/browse/JENKINS-34741
  MinicondaEnv(String p, String m, String l) {
    this.pythonVersion = p
    this.minicondaVersion = m
    this.lsstswRef = l
  }

  /**
   * Generate a single string description of miniconda env.
   */
  String slug() {
    "miniconda${pythonVersion}-${minicondaVersion}-${lsstswRef}"
  }
}
