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
  util.requireParams([
    'BRANCH',
    'PRODUCT',
    'SKIP_DEMO',
    'SKIP_DOCS',
    'TIMEOUT',
  ])

  def skipDocs  = params.SKIP_DOCS.toBoolean()
  def timelimit = params.TIMEOUT.toInteger()

  def versiondbPush = 'false'
  def versiondbRepo = util.githubSlugToUrl(config.versiondb_repo_slug, 'https')

  if (! params.NO_VERSIONDB_PUSH) {
    versiondbPush = 'true'
    versiondbRepo = util.githubSlugToUrl(config.versiondb_repo_slug, 'ssh')
  }

  def can       = config.canonical
  def awsImage  = 'lsstsqre/awscli'
  def slug      = "${can.label}.py${can.python}"

  def run = {
    ws(config.canonical_workspace) {
      def cwd = pwd()

      def buildParams = [
        EUPS_PKGROOT:       "${cwd}/distrib",
        VERSIONDB_REPO:      versiondbRepo,
        VERSIONDB_PUSH:      versiondbPush,
        GIT_SSH_COMMAND:     'ssh -o StrictHostKeyChecking=no',
        LSST_JUNIT_PREFIX:   slug,
        LSST_PYTHON_VERSION: can.python,
        LSST_COMPILER:       can.compiler,
        // XXX this should be renamed in lsstsw to make it clear that its
        // setting a github repo slug
        REPOSFILE_REPO:      "${config.reposfile_repo_slug}",
        BRANCH:              BRANCH,
        PRODUCT:             PRODUCT,
        SKIP_DEMO:           SKIP_DEMO,
        SKIP_DOCS:           SKIP_DOCS,
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
        util.insideWrap(can.image) {
          // only setup sshagent if we are going to push
          if (versiondbPush) {
            withVersiondbCredentials(runJW)
          } else {
            runJW()
          }
        } // util.insideWrap
      } // stage('build')

      stage('push docs') {
        if (skipDocs) {
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
              // and doesn't work with util.insideWrap.  However, the aws cli
              // seems to work OK without trying to lookup the username.
              docker.image(awsImage).inside {
                // alpine does not include bash by default
                util.posixSh '''
                  # provides DOC_PUSH_PATH
                  . ./ci-scripts/settings.cfg.sh

                  aws s3 cp --recursive \
                    "${DOC_PUSH_PATH}/" \
                    "s3://${DOXYGEN_S3_BUCKET}/stack/doxygen/"
                '''
              } // util.insideWrap
            } // withEnv
          } // withCredentials
        }
      } // stage('push docs')
    } // ws
  } // run

  node(config.canonical_node_label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
} // notify.wrap
