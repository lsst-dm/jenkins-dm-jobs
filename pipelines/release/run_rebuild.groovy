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
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'REFS',
    'PREP_ONLY',
    'PRODUCTS',
    'BUILD_DOCS',
    'TIMEOUT',
  ])

  String refs       = params.REFS
  Boolean prepOnly  = params.PREP_ONLY
  String products   = params.PRODUCTS
  Boolean buildDocs = params.BUILD_DOCS
  Integer timelimit = params.TIMEOUT

  // not a normally exposed job param
  Boolean versiondbPush = (! params.NO_VERSIONDB_PUSH?.toBoolean())
  // default to safe
  def versiondbRepo = util.githubSlugToUrl(
    scipipe.versiondb.github_repo,
    'https'
  )
  if (versiondbPush) {
    versiondbRepo = util.githubSlugToUrl(scipipe.versiondb.github_repo, 'ssh')
  }

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

      def buildParams = [
        EUPS_PKGROOT:       "${cwd}/distrib",
        GIT_SSH_COMMAND:     'ssh -o StrictHostKeyChecking=no',
        LSST_BUILD_DOCS:     buildDocs,
        LSST_COMPILER:       lsstswConfig.compiler,
        LSST_JUNIT_PREFIX:   slug,
        LSST_PREP_ONLY:      prepOnly,
        LSST_PRODUCTS:       products,
        LSST_PYTHON_VERSION: lsstswConfig.python,
        LSST_SPLENV_REF:     splenvRef,
        LSST_REFS:           refs,
        VERSIONDB_PUSH:      versiondbPush,
        VERSIONDB_REPO:      versiondbRepo,
      ]

      def runJW = {
        // note that util.jenkinsWrapper() clones the ci-scripts repo, which is
        // used by the push docs stage
        try {
          util.jenkinsWrapper(buildParams)
        } finally {
          util.jenkinsWrapperPost()
        }
      }

      def withVersiondbCredentials = { invoke ->
        sshagent (credentials: ['github-jenkins-versiondb']) {
          invoke()
        }
      }

      stage('build') {
        util.insideDockerWrap(
          image: lsstswConfig.image,
          pull: true,
        ) {
          // only setup sshagent if we are going to push
          if (versiondbPush) {
            withVersiondbCredentials(runJW)
          } else {
            runJW()
          }
        } // util.insideDockerWrap
      } // stage('build')

      stage('push docs') {
        if (buildDocs) {
          withCredentials([[
            $class: 'UsernamePasswordMultiBinding',
            credentialsId: 'aws-doxygen-push',
            usernameVariable: 'AWS_ACCESS_KEY_ID',
            passwordVariable: 'AWS_SECRET_ACCESS_KEY'
          ],
          [
            $class: 'StringBinding',
            credentialsId: 'doxygen-push-bucket',
            variable: 'DOXYGEN_S3_BUCKET'
          ]]) {
            withEnv([
              "EUPS_PKGROOT=${cwd}/distrib",
              "HOME=${cwd}/home",
            ]) {
              // the current iteration of the awscli container is alpine based
              // and doesn't work with util.insideDockerWrap.  However, the aws
              // cli seems to work OK without trying to lookup the username.
              docker.image(util.defaultAwscliImage()).inside {
                // alpine does not include bash by default
                util.posixSh '''
                  # provides DOC_PUSH_PATH
                  . ./ci-scripts/settings.cfg.sh

                  aws s3 cp \
                    --only-show-errors \
                    --recursive \
                    "${DOC_PUSH_PATH}/" \
                    "s3://${DOXYGEN_S3_BUCKET}/stack/doxygen/"
                '''
              } // util.insideDockerWrap
            } // withEnv
          } // withCredentials
        }
      } // stage('push docs')
    } // ws
  } // run

  util.nodeWrap(lsstswConfig.label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
