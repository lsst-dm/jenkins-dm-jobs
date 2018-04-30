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
  def eupsTag = 'qserv-dev'
  def bx = null

  try {
    timeout(time: 30, unit: 'HOURS') {
      def product         = 'qserv_distrib'
      def tarballProducts = product

      def retries = 3
      def rebuildId = null
      def buildJob = 'release/run-rebuild'
      def publishJob = 'release/run-publish'

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

        parallel pub
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
