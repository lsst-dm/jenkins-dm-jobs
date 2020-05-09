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
    scipipe_old = util.scipipeConfig(params.OLD_MATRIX)
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'MANIFEST_ID',
    'EUPSPKG_SOURCE',
    'PRODUCTS',
    'EUPS_TAG',
    'TIMEOUT',
  ])

  String manifestId    = params.MANIFEST_ID
  String eupspkgSource = params.EUPSPKG_SOURCE
  String products      = params.PRODUCTS
  String eupsTag       = params.EUPS_TAG
  Integer timelimit    = params.TIMEOUT

  // not a normally exposed job param
  Boolean pushS3 = (! params.NO_PUSH?.toBoolean())

  def canonical    = scipipe.canonical
  def lsstswConfig = canonical.lsstsw_config

  def splenvRef = lsstswConfig.splenv_ref
  if (params.SPLENV_REF) {
    splenvRef = params.SPLENV_REF
  }

  def slug = util.lsstswConfigSlug(lsstswConfig)

  def run = {
    ws(canonical.workspace) {
      def cwd = pwd()

      stage('publish') {
        dir('lsstsw') {
          util.cloneLsstsw()
        }

        def pkgroot = "${cwd}/distrib"
        def tagDir  = "${pkgroot}/tags"

        // remove any pre-existing eups tags to prevent them from being
        // [re]published
        // the src pkgroot has tags under ./tags/
        dir(tagDir) {
          deleteDir()
        }

        def env = [
          "HOME=${cwd}/home",
          "EUPS_PKGROOT=${pkgroot}",
          "EUPS_USERDATA=${cwd}/home/.eups_userdata",
          "EUPSPKG_SOURCE=${eupspkgSource}",
          "LSST_SPLENV_REF=${splenvRef}",
          "MANIFEST_ID=${manifestId}",
          "EUPS_TAG=${eupsTag}",
          "PRODUCTS=${products}",
        ]

        withEnv(env) {
          util.insideDockerWrap(
            image: lsstswConfig.image,
            pull: true,
          ) {
            util.bash '''
              ARGS=()
              ARGS+=('-b' "$MANIFEST_ID")
              ARGS+=('-t' "$EUPS_TAG")
              # enable debug output
              ARGS+=('-d')
              # split whitespace separated EUPS products into separate array
              # elements by not quoting
              ARGS+=($PRODUCTS)

              export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"

              # setting up the same environment used in the previous build step
              #   this can be retrived using the -b option.
              # (note: bin/setup.sh is now deprecated)
              source ./lsstsw/bin/envconfig -n "lsst-scipipe-$LSST_SPLENV_REF"

              publish "${ARGS[@]}"
            '''
          }
        } // util.insideDockerWrap
      } // stage('publish')

      stage('push packages') {
        if (pushS3) {
          withCredentials([[
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'aws-eups-push',
            usernameVariable: 'AWS_ACCESS_KEY_ID',
            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
          ],
          [
            $class: 'StringBinding',
            credentialsId: 'eups-push-bucket',
            variable: 'EUPS_S3_BUCKET'
          ]]) {
            def env = [
              "EUPS_PKGROOT=${cwd}/distrib",
              "HOME=${cwd}/home",
              "EUPS_S3_OBJECT_PREFIX=stack/src/"
            ]
            withEnv(env) {
              docker.image(util.defaultAwscliImage()).inside {
                // alpine does not include bash by default
                util.posixSh '''
                  aws s3 cp \
                    --only-show-errors \
                    --recursive \
                    "${EUPS_PKGROOT}/" \
                    "s3://${EUPS_S3_BUCKET}/${EUPS_S3_OBJECT_PREFIX}"
                '''
              } // .inside
            } // withEnv
          } // withCredentials
        } else {
          echo "skipping s3 push."
        }
      } // stage('push packages')
    } // ws
  } // run

  util.nodeWrap(lsstswConfig.label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
