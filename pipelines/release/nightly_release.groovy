def notify = null

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
  }
}

try {
  notify.started()

  def gitTag = null
  def eupsTag = null
  def bx = null

  try {
    timeout(time: 30, unit: 'HOURS') {
      def product = 'lsst_distrib qserv_distrib'
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
          timeout(time: 6, unit: 'HOURS') {
            def result = build job: buildJob,
              parameters: [
                string(name: 'PRODUCT', value: product),
                booleanParam(name: 'SKIP_DEMO', value: false),
                booleanParam(name: 'SKIP_DOCS', value: false),
              ],
              wait: true
            rebuildId = result.id
          }
        }
      }

      stage('parse bNNNN') {
        node('jenkins-master') {
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
            timeout(time: 1, unit: 'HOURS') {
              util.tagProduct(bx, eupsTag, product, publishJob)
            }
          }
        }
        pub['d_latest'] = {
          retry(retries) {
            timeout(time: 1, unit: 'HOURS') {
              util.tagProduct(bx, 'd_latest', product, publishJob)
            }
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
          timeout(time: 1, unit: 'HOURS') {
            // needs eups distrib tag to be sync'd from s3 -> k8s volume
            build job: 'release/tag-git-repos',
              parameters: [
                string(name: 'BUILD_ID', value: bx),
                string(name: 'GIT_TAG', value: gitTag),
                booleanParam(name: 'DRY_RUN', value: true)
              ]
          }
        }
      }

      stage("build eups tarballs") {
        def operatingsystem = [
          'centos-7',
          'centos-6',
          'osx-10.11',
        ]

        def pyenv = [
          new MinicondaEnv('2', '4.2.12', '7c8e67'), // keep until v14_0
          new MinicondaEnv('3', '4.2.12', '7c8e67'), // keep until v14_0
          new MinicondaEnv('2', '4.3.21', '10a4fa6'),
          new MinicondaEnv('3', '4.3.21', '10a4fa6'),
        ]

        def platform = [:]

        operatingsystem.each { os ->
          pyenv.each { py ->
            platform["${os}.${py.slug()}"] = {
              retry(retries) {
                build job: 'release/tarball',
                  parameters: [
                    string(name: 'PRODUCT', value: 'lsst_distrib'),
                    string(name: 'EUPS_TAG', value: eupsTag),
                    booleanParam(name: 'SMOKE', value: true),
                    booleanParam(name: 'RUN_DEMO', value: true),
                    booleanParam(name: 'RUN_SCONS_CHECK', value: true),
                    booleanParam(name: 'PUBLISH', value: true),
                    string(name: 'PYVER', value: py.pythonVersion),
                    string(name: 'MINIVER', value: py.minicondaVersion),
                    string(name: 'LSSTSW_REF', value: py.lsstswRef),
                    string(name: 'OS', value: os),
                    string(name: 'TIMEOUT', value: '6'), // hours
                  ]
              }
            }
          }
        }

        parallel platform
      }

      stage('wait for s3 sync') {
        sleep time: 15, unit: 'MINUTES'
      }

      stage('build stack image') {
        retry(retries) {
          timeout(time: 1, unit: 'HOURS') {
            build job: 'release/docker/build-stack',
              parameters: [
                string(name: 'TAG', value: eupsTag)
              ]
          }
        }
      }

      stage('build jupyterlabdemo image') {
        retry(retries) {
          timeout(time: 1, unit: 'HOURS') {
            // based on lsstsqre/stack image
            build job: 'sqre/infrastructure/build-jupyterlabdemo',
              parameters: [
                string(name: 'TAG', value: eupsTag),
                booleanParam(name: 'NO_PUSH', value: false),
              ]
          }
        }
      }
    } // timeout
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      node('jenkins-master') {
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
} catch (e) {
  // If there was an exception thrown, the build failed
  currentBuild.result = "FAILED"
  throw e
} finally {
  echo "result: ${currentBuild.result}"
  switch(currentBuild.result) {
    case null:
    case 'SUCCESS':
      notify.success()
      break
    case 'ABORTED':
      notify.aborted()
      break
    case 'FAILURE':
      notify.failure()
      break
    default:
      notify.failure()
  }
}

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
