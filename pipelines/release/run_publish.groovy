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
    sqre = util.readYamlFile 'etc/sqre/config.yaml'
  }
}

notify.wrap {
  util.requireParams([
    'MANIFEST_ID',
    'EUPSPKG_SOURCE',
    'PRODUCT',
    'EUPS_TAG',
    'TIMEOUT',
  ])

  String manifestId    = params.MANIFEST_ID
  String eupspkgSource = params.EUPSPKG_SOURCE
  String product       = params.PRODUCT
  String eupsTag       = params.EUPS_TAG
  Integer timelimit    = params.TIMEOUT

  // not a normally exposed job param
  Boolean pushS3 = (! params.NO_PUSH?.toBoolean())

  def canonical    = config.canonical
  def lsstswConfig = canonical.lsstsw_config

  def slug = util.lsstswConfigSlug(lsstswConfig)
  def awscliImage = sqre.awscli.docker_registry.repo
  awscliImage += ":${sqre.awscli.docker_registry.tag}"

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
          "EUPS_PKGROOT=${pkgroot}",
          "EUPS_USERDATA=${cwd}/home/.eups_userdata",
          "EUPSPKG_SOURCE=${eupspkgSource}",
          "MANIFEST_ID=${manifestId}",
          "EUPS_TAG=${eupsTag}",
          "PRODUCT=${product}",
        ]

        withCredentials([[
          $class: 'StringBinding',
          credentialsId: 'cmirror-s3-bucket',
          variable: 'CMIRROR_S3_BUCKET'
        ]]) {
          withEnv(env) {
            util.insideWrap(lsstswConfig.image) {
              util.bash '''
                ARGS=()
                ARGS+=('-b' "$MANIFEST_ID")
                ARGS+=('-t' "$EUPS_TAG")
                # enable debug output
                ARGS+=('-d')
                # split whitespace separated EUPS products into separate array
                # elements by not quoting
                ARGS+=($PRODUCT)

                export EUPSPKG_SOURCE="$EUPSPKG_SOURCE"

                # setup.sh will unset $PRODUCTS
                source ./lsstsw/bin/setup.sh

                publish "${ARGS[@]}"
              '''
            }
          } // util.insideWrap
        }// withCredentials([[
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
              docker.image(awscliImage).inside {
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

  node(lsstswConfig.label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
