node('jenkins-manager') {
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
  Integer timelimit    = Integer.parseInt(params.TIMEOUT)

  // not a normally exposed job param
  Boolean pushS3 = (! params.NO_PUSH?.toBoolean())

  def canonical    = scipipe.canonical
  def lsstswConfigs = canonical.lsstsw_config

  def splenvRef = lsstswConfigs.splenv_ref
  if (params.SPLENV_REF) {
    splenvRef = params.SPLENV_REF
  }
  def rubinEnvVer = splenvRef
  if (params.RUBINENV_VER) {
    rubinEnvVer = params.RUBINENV_VER
  }
  def matrix = [:]
  lsstswConfigs.each{ lsstswConfig ->
    def slug = util.lsstswConfigSlug(lsstswConfig)
    matrix[slug] ={
    def run = {
    def workingDir = canonical.workspace + "/${lsstswConfig.display_name}"
    ws(workingDir) {
      def cwd = pwd()
      def pkgroot = "${cwd}/distrib"

      stage('create packages') {
        dir('lsstsw') {
          util.cloneLsstsw()
        }

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
          "RUBINENV_VER=${rubinEnvVer}",
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
        if (false) {
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
              "EUPS_PKGROOT=${pkgroot}",
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
  }
  }
  parallel matrix
} // notify.wrap